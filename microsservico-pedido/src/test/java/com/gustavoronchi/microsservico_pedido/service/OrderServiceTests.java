package com.gustavoronchi.microsservico_pedido.service;

import com.gustavoronchi.microsservico_pedido.client.StockClient;
import com.gustavoronchi.microsservico_pedido.domain.entities.OutboxEvent;
import com.gustavoronchi.microsservico_pedido.domain.repository.OrderRepository;
import com.gustavoronchi.microsservico_pedido.domain.repository.OutboxEventRepository;
import com.gustavoronchi.microsservico_pedido.dto.OrderItemRequestDTO;
import com.gustavoronchi.microsservico_pedido.dto.OrderRequestDTO;
import com.gustavoronchi.microsservico_pedido.dto.ProductPriceDTO;
import com.gustavoronchi.microsservico_pedido.dto.StockItemRequestDTO;
import com.gustavoronchi.microsservico_pedido.messaging.OrderCreatedEvent;
import com.gustavoronchi.microsservico_pedido.resource.OrderResource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DataJpaTest(showSql = false, properties = "spring.sql.init.mode=never")
@Import({OrderService.class, OrderServiceTests.JsonConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OrderServiceTests {

    @Autowired
    private OrderService orderService;
    @Autowired
    private OrderRepository orders;
    @Autowired
    private JsonMapper jsonMapper;
    @MockitoSpyBean
    private OutboxEventRepository events;
    @MockitoBean
    private StockClient stockClient;

    private MockMvc mvc;
    private final UUID firstProductId = UUID.randomUUID();
    private final UUID secondProductId = UUID.randomUUID();
    private ProductPriceDTO firstPrice;

    @BeforeEach
    void setUp() {
        events.deleteAll();
        orders.deleteAll();
        firstPrice = new ProductPriceDTO(firstProductId, new BigDecimal("10.00"));
        when(stockClient.findPrices(any())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return List.of(firstPrice, new ProductPriceDTO(secondProductId, new BigDecimal("20.00")));
        });
        mvc = MockMvcBuilders.standaloneSetup(new OrderResource(orderService)).build();
    }

    @Test
    void createsOrderAndOutboxWithServerPrices() throws Exception {
        OrderRequestDTO request = new OrderRequestDTO(UUID.randomUUID(), List.of(
                new OrderItemRequestDTO(firstProductId, 2),
                new OrderItemRequestDTO(secondProductId, 1),
                new OrderItemRequestDTO(firstProductId, 3)));

        var response = mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.totalValue").value(70.00))
                .andReturn().getResponse();

        UUID orderId = UUID.fromString(jsonMapper.readTree(response.getContentAsString()).get("orderId").asString());
        assertThat(response.getHeader("Location")).endsWith("/orders/" + orderId);
        verify(stockClient).findPrices(List.of(firstProductId, secondProductId));
        assertThat(events.findAll()).hasSize(1);
        OutboxEvent event = events.findAll().getFirst();
        assertThat(event.getOrderId()).isEqualTo(orderId);
        assertThat(event.getExchange()).isEqualTo("order.created");
        assertThat(event.getPublishedAt()).isNull();
        OrderCreatedEvent message = jsonMapper.readValue(event.getPayload(), OrderCreatedEvent.class);
        assertThat(message.getEventId()).isEqualTo(event.getEventId());
        assertThat(message.getOrderId()).isEqualTo(orderId);
        assertThat(message.getOccurredAt()).isEqualTo(event.getOccurredAt());
        assertThat(message.getAmount()).isEqualByComparingTo("70.00");
        assertThat(message.getCurrency()).isEqualTo("BRL");
        assertThat(message.getItems()).extracting(StockItemRequestDTO::getQuantity).containsExactly(2, 1, 3);

        firstPrice.setPrice(new BigDecimal("999.00"));
        var saved = orderService.findById(orderId);
        assertThat(saved.getTotalValue()).isEqualByComparingTo("70.00");
        assertThat(saved.getItems()).filteredOn(item -> item.getProductId().equals(firstProductId))
                .allSatisfy(item -> assertThat(item.getUnitValue()).isEqualByComparingTo("10.00"));
    }

    @Test
    void outboxFailureRollsBackOrderAndItems() {
        doThrow(new DataIntegrityViolationException("Falha simulada na outbox"))
                .when(events).save(any(OutboxEvent.class));

        assertThatThrownBy(() -> orderService.createOrder(request(1)))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(orders.findAll()).isEmpty();
        assertThat(events.findAll()).isEmpty();
    }

    @Test
    void unavailableCatalogReturns503WithoutCreatingOrder() throws Exception {
        when(stockClient.findPrices(any())).thenThrow(new ResourceAccessException("Timeout"));

        mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(request(1))))
                .andExpect(status().isServiceUnavailable());

        assertThat(orders.findAll()).isEmpty();
        assertThat(events.findAll()).isEmpty();
    }

    @Test
    void nonexistentProductReturns400WithoutCreatingOrder() throws Exception {
        when(stockClient.findPrices(any())).thenThrow(HttpClientErrorException.create(
                HttpStatus.NOT_FOUND, "Produto inexistente", null, new byte[0], StandardCharsets.UTF_8));

        mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(request(1))))
                .andExpect(status().isBadRequest());

        assertThat(orders.findAll()).isEmpty();
        assertThat(events.findAll()).isEmpty();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {0, -1})
    void invalidQuantityDoesNotQueryCatalog(Integer quantity) throws Exception {
        mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(request(quantity))))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(stockClient);
        assertThat(orders.findAll()).isEmpty();
        assertThat(events.findAll()).isEmpty();
    }

    @Test
    void incompleteCatalogResponseDoesNotPersistPartialOrder() {
        when(stockClient.findPrices(any())).thenReturn(List.of());

        assertThatThrownBy(() -> orderService.createOrder(request(1)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(orders.findAll()).isEmpty();
        assertThat(events.findAll()).isEmpty();
    }

    private OrderRequestDTO request(Integer quantity) {
        return new OrderRequestDTO(UUID.randomUUID(), List.of(new OrderItemRequestDTO(firstProductId, quantity)));
    }

    @TestConfiguration
    static class JsonConfig {
        @Bean
        JsonMapper jsonMapper() {
            return JsonMapper.builder().build();
        }
    }
}