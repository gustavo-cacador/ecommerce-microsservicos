package com.gustavoronchi.microsservico_pedido.service;

import com.gustavoronchi.microsservico_pedido.client.StockClient;
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
import org.junit.jupiter.params.provider.MethodSource;
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
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
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

    private MockMvc mvc;

    @DynamicPropertySource
    static void stockProperties(DynamicPropertyRegistry registry) {
        registry.add("clients.estoque-service.url", () -> "http://127.0.0.1:" + stockPort);
    }

    @BeforeEach
    void setUp() {
        events.deleteAll();
        orders.deleteAll();
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
                () -> createOrder(REQUEST, key).andExpect(status().isServiceUnavailable()));
        assertThat(orders.count()).isZero();
        assertThat(events.count()).isZero();
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
                    if (current == Failure.SLOW_HEADERS) {
                        release.await(10, TimeUnit.SECONDS);
                    }
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(current == Failure.HTTP_503 ? 503 : 200, body.length);
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

    enum Failure { SLOW_HEADERS, SLOW_BODY, CONNECTION_REFUSED, HTTP_503 }

    @TestConfiguration
    static class JsonConfig {
        @Bean
        JsonMapper jsonMapper() {
            return JsonMapper.builder().build();
        }
    }
}
