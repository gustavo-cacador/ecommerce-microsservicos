package com.gustavoronchi.microsservico_pedido.service;

import com.gustavoronchi.microsservico_pedido.client.StockClient;
import com.gustavoronchi.microsservico_pedido.domain.entities.CatalogProduct;
import com.gustavoronchi.microsservico_pedido.domain.repository.CatalogProductRepository;
import com.gustavoronchi.microsservico_pedido.domain.repository.OrderRepository;
import com.gustavoronchi.microsservico_pedido.domain.repository.OutboxEventRepository;
import com.gustavoronchi.microsservico_pedido.resource.OrderResource;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DataJpaTest(showSql = false, properties = {
        "spring.sql.init.mode=never",
        "clients.estoque-service.connect-timeout-ms=300",
        "clients.estoque-service.read-timeout-ms=500"
})
@Import({OrderService.class, StockClient.class, OrderStockTimeoutTests.JsonConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OrderStockTimeoutTests {

    private static final String PRODUCT_ID = "11111111-1111-1111-1111-111111111111";
    private static final String REQUEST = """
            {"clientId":"22222222-2222-2222-2222-222222222222",
             "items":[{"productId":"%s","quantity":2}]}
            """.formatted(PRODUCT_ID);
    private static final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private static final List<String> stockRequests = new CopyOnWriteArrayList<>();
    private static volatile Failure failure;
    private static volatile CountDownLatch releaseResponse;
    private static HttpServer stock = startStock(0);
    private static final int stockPort = stock.getAddress().getPort();

    @Autowired
    private OrderService orderService;
    @Autowired
    private OrderRepository orders;
    @Autowired
    private OutboxEventRepository events;
    @Autowired
    private CatalogProductRepository catalogProducts;

    private MockMvc mvc;

    @DynamicPropertySource
    static void stockProperties(DynamicPropertyRegistry registry) {
        registry.add("clients.estoque-service.url", () -> "http://127.0.0.1:" + stockPort);
    }

    @BeforeEach
    void setUp() {
        events.deleteAll();
        orders.deleteAll();
        catalogProducts.deleteAll();
        failure = null;
        releaseResponse = new CountDownLatch(1);
        stockRequests.clear();
        mvc = MockMvcBuilders.standaloneSetup(new OrderResource(orderService)).build();
    }

    @AfterEach
    void resetStock() {
        releaseResponse.countDown();
        if (stock == null) {
            stock = startStock(stockPort);
        }
    }

    @AfterAll
    static void stopStock() {
        stock.stop(0);
        executor.shutdownNow();
    }

    static Stream<Arguments> failures() {
        return Arrays.stream(Failure.values())
                .flatMap(mode -> Stream.of(Arguments.of(mode, null), Arguments.of(mode, "retry-key")));
    }

    @ParameterizedTest
    @MethodSource("failures")
    void stockFailureSavesNothingAndAllowsRetry(Failure mode, String key) throws Exception {
        failure = mode;
        if (mode == Failure.CONNECTION_REFUSED) {
            stock.stop(0);
            stock = null;
        }

        // The server withholds its response for 10s; the configured client must give up first.
        assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> createOrder(REQUEST, key).andExpect(
                        mode == Failure.HTTP_404 ? status().isBadRequest() : status().isServiceUnavailable()));
        assertThat(orders.count()).isZero();
        assertThat(events.count()).isZero();
        assertThat(catalogProducts.count()).isZero();
        assertThat(stockRequests).hasSize(mode == Failure.CONNECTION_REFUSED ? 0 : 1);

        releaseResponse.countDown();
        failure = null;
        if (stock == null) {
            stock = startStock(stockPort);
        }
        createOrder(REQUEST, key).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.totalValue").value(20.00));
        assertThat(orders.count()).isEqualTo(1);
        assertThat(events.count()).isEqualTo(1);
        assertThat(catalogProducts.count()).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(Failure.class)
    void stockFailurePreservesExpiredCopyWithoutCreatingOrder(Failure mode) throws Exception {
        UUID productId = UUID.fromString(PRODUCT_ID);
        Instant updatedAt = Instant.now().minus(Duration.ofHours(25)).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        catalogProducts.saveAndFlush(new CatalogProduct(productId, new BigDecimal("8.00"), 100, updatedAt));
        failure = mode;
        if (mode == Failure.CONNECTION_REFUSED) {
            stock.stop(0);
            stock = null;
        }

        assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> createOrder(REQUEST, "cached-product").andExpect(
                        mode == Failure.HTTP_404 ? status().isBadRequest() : status().isServiceUnavailable()));

        assertThat(catalogProducts.count()).isEqualTo(1);
        CatalogProduct saved = catalogProducts.findById(productId).orElseThrow();
        assertThat(saved.getPrice()).isEqualByComparingTo("8.00");
        assertThat(saved.getAvailableStock()).isEqualTo(100);
        assertThat(saved.getUpdatedAt()).isEqualTo(updatedAt);
        assertThat(orders.count()).isZero();
        assertThat(events.count()).isZero();
    }

    @ParameterizedTest
    @EnumSource(value = Failure.class, names = {"SLOW_HEADERS", "SLOW_BODY", "CONNECTION_REFUSED", "HTTP_500", "HTTP_503"})
    void technicalFailureCreatesOrderAndOutboxUsingFreshCopy(Failure mode) throws Exception {
        Instant updatedAt = Instant.now().minus(Duration.ofHours(23)).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        UUID productId = UUID.fromString(PRODUCT_ID);
        catalogProducts.saveAndFlush(new CatalogProduct(productId, new BigDecimal("8.00"), 0, updatedAt));
        failure = mode;
        if (mode == Failure.CONNECTION_REFUSED) {
            stock.stop(0);
            stock = null;
        }

        assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> createOrder(REQUEST, "fallback-key").andExpect(status().isCreated())
                        .andExpect(jsonPath("$.status").value("CREATED"))
                        .andExpect(jsonPath("$.items[0].unitValue").value(8.00))
                        .andExpect(jsonPath("$.totalValue").value(16.00)));

        var order = orders.findByIdempotencyKey("fallback-key").orElseThrow();
        assertThat(orders.count()).isEqualTo(1);
        assertThat(events.findAll()).singleElement().satisfies(event -> {
            assertThat(event.getOrderId()).isEqualTo(order.getId());
            assertThat(event.getExchange()).isEqualTo("order.created");
            assertThat(event.getPublishedAt()).isNull();
            assertThat(JsonMapper.builder().build().readTree(event.getPayload()).get("amount").decimalValue())
                    .isEqualByComparingTo("16.00");
        });
        assertThat(catalogProducts.findById(productId).orElseThrow().getUpdatedAt()).isEqualTo(updatedAt);
    }

    @ParameterizedTest
    @EnumSource(value = Failure.class, names = {"HTTP_400", "HTTP_401", "HTTP_404", "HTTP_409", "HTTP_429", "INVALID_JSON", "TRUNCATED_JSON", "INVALID_ENCODING"})
    void nonTechnicalFailureDoesNotUseFreshCopy(Failure mode) throws Exception {
        catalogProducts.saveAndFlush(new CatalogProduct(UUID.fromString(PRODUCT_ID), new BigDecimal("8.00"), 50, Instant.now()));
        failure = mode;

        createOrder(REQUEST, "invalid-key").andExpect(
                mode == Failure.HTTP_404 ? status().isBadRequest() : status().isServiceUnavailable());

        assertThat(orders.count()).isZero();
        assertThat(events.count()).isZero();
        assertThat(catalogProducts.findById(UUID.fromString(PRODUCT_ID)).orElseThrow().getPrice())
                .isEqualByComparingTo("8.00");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = "fallback-key")
    void fallbackPreservesPricesAndIdempotencyAfterRecovery(String key) throws Exception {
        catalogProducts.saveAndFlush(new CatalogProduct(UUID.fromString(PRODUCT_ID), new BigDecimal("8.00"), 0, Instant.now()));
        failure = Failure.HTTP_503;
        var first = createOrder(REQUEST, key).andExpect(status().isCreated()).andReturn().getResponse();
        String orderId = JsonMapper.builder().build().readTree(first.getContentAsString()).get("orderId").asString();
        var event = events.findAll().getFirst();
        catalogProducts.deleteAll();
        if (key != null) {
            createOrder(REQUEST, key).andExpect(status().isCreated())
                    .andExpect(jsonPath("$.orderId").value(orderId));
            createOrder(REQUEST.replace("\"quantity\":2", "\"quantity\":3"), key).andExpect(status().isConflict());
            assertThat(stockRequests).hasSize(1);
        }

        failure = null;
        createOrder(REQUEST, "recovered-key").andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalValue").value(20.00));
        assertThat(catalogProducts.findById(UUID.fromString(PRODUCT_ID)).orElseThrow().getPrice()).isEqualByComparingTo("10.00");
        if (key != null) {
            createOrder(REQUEST, key).andExpect(status().isCreated())
                    .andExpect(jsonPath("$.orderId").value(orderId))
                    .andExpect(jsonPath("$.totalValue").value(16.00));
            assertThat(stockRequests).hasSize(2);
        }
        var saved = orderService.findById(UUID.fromString(orderId));
        assertThat(saved.getTotalValue()).isEqualByComparingTo("16.00");
        assertThat(saved.getItems().getFirst().getUnitValue()).isEqualByComparingTo("8.00");
        assertThat(orders.count()).isEqualTo(2);
        assertThat(events.count()).isEqualTo(2);
        assertThat(events.findById(event.getEventId()).orElseThrow().getPayload()).isEqualTo(event.getPayload());
    }

    @Test
    void successAndIdempotencySurviveStockOutage() throws Exception {
        var first = createOrder(REQUEST, "saved-key").andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalValue").value(20.00)).andReturn().getResponse();
        var saved = orders.findByIdempotencyKey("saved-key").orElseThrow();
        var event = events.findAll().getFirst();
        assertThat(event.getOrderId()).isEqualTo(saved.getId());
        assertThat(event.getExchange()).isEqualTo("order.created");
        assertThat(event.getPublishedAt()).isNull();
        assertThat(stockRequests).containsExactly("POST /products/prices [\"" + PRODUCT_ID + "\"]");
        stock.stop(0);
        stock = null;

        var repeated = createOrder(REQUEST, "saved-key").andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderId").value(saved.getId().toString()))
                .andReturn().getResponse();
        assertThat(repeated.getContentAsString()).isEqualTo(first.getContentAsString());
        assertThat(repeated.getHeader("Location")).isEqualTo(first.getHeader("Location"));
        createOrder(REQUEST.replace("\"quantity\":2", "\"quantity\":3"), "saved-key")
                .andExpect(status().isConflict());
        assertThat(orders.count()).isEqualTo(1);
        assertThat(events.findAll()).singleElement()
                .satisfies(result -> assertThat(result.getEventId()).isEqualTo(event.getEventId()));
    }

    @Test
    void insufficientAvailabilityStillReturnsConflictWithoutSaving() throws Exception {
        createOrder(REQUEST.replace("\"quantity\":2", "\"quantity\":51"), "stock-key")
                .andExpect(status().isConflict());
        assertThat(stockRequests).hasSize(1);
        assertThat(orders.count()).isZero();
        assertThat(events.count()).isZero();
    }

    private ResultActions createOrder(String body, String key) throws Exception {
        var request = post("/orders").contentType(MediaType.APPLICATION_JSON).content(body);
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return mvc.perform(request);
    }

    private static HttpServer startStock(int port) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
            server.setExecutor(executor);
            server.createContext("/products/prices", exchange -> {
                Failure current = failure;
                CountDownLatch release = releaseResponse;
                try (exchange) {
                    stockRequests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI() + " "
                            + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    byte[] body = ("[{\"productId\":\"" + PRODUCT_ID
                            + "\",\"price\":10.00,\"availableStock\":50}]").getBytes(StandardCharsets.UTF_8);
                    if (current == Failure.INVALID_JSON) {
                        body = "invalid-json".getBytes(StandardCharsets.UTF_8);
                    }
                    if (current == Failure.TRUNCATED_JSON) {
                        body = "[{\"productId\":".getBytes(StandardCharsets.UTF_8);
                    }
                    if (current == Failure.INVALID_ENCODING) {
                        body = new byte[] {0, 0, (byte) 0xFE, (byte) 0xFF, 0, 0x11, 0, 0};
                    }
                    if (current == Failure.SLOW_HEADERS) {
                        release.await(10, TimeUnit.SECONDS);
                    }
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    int status = current != null && current.name().startsWith("HTTP_")
                            ? Integer.parseInt(current.name().substring(5)) : 200;
                    exchange.sendResponseHeaders(status, body.length);
                    if (current == Failure.SLOW_BODY) {
                        exchange.getResponseBody().write(body, 0, 1);
                        exchange.getResponseBody().flush();
                        release.await(10, TimeUnit.SECONDS);
                        exchange.getResponseBody().write(body, 1, body.length - 1);
                    } else {
                        exchange.getResponseBody().write(body);
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } catch (IOException ignored) {
                    // A timed-out client can close the connection before the delayed response.
                }
            });
            server.start();
            return server;
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot start isolated stock server", ex);
        }
    }

    enum Failure { SLOW_HEADERS, SLOW_BODY, CONNECTION_REFUSED, HTTP_500, HTTP_503,
        HTTP_400, HTTP_401, HTTP_404, HTTP_409, HTTP_429, INVALID_JSON, TRUNCATED_JSON, INVALID_ENCODING }

    @TestConfiguration
    static class JsonConfig {
        @Bean
        JsonMapper jsonMapper() {
            return JsonMapper.builder().build();
        }
    }
}
