package com.gustavoronchi.microsservico_pedido.messaging;

import com.gustavoronchi.microsservico_pedido.client.StockClient;
import com.gustavoronchi.microsservico_pedido.config.RabbitMQConfig;
import com.gustavoronchi.microsservico_pedido.domain.entities.CatalogProduct;
import com.gustavoronchi.microsservico_pedido.domain.repository.CatalogProductRepository;
import com.gustavoronchi.microsservico_pedido.domain.repository.OrderRepository;
import com.gustavoronchi.microsservico_pedido.domain.repository.OutboxEventRepository;
import com.gustavoronchi.microsservico_pedido.dto.OrderItemRequestDTO;
import com.gustavoronchi.microsservico_pedido.dto.OrderRequestDTO;
import com.gustavoronchi.microsservico_pedido.enums.StatusOrder;
import com.gustavoronchi.microsservico_pedido.messaging.*;
import com.gustavoronchi.microsservico_pedido.service.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.ResourceAccessException;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

// Requer um broker isolado informado por RABBITMQ_TEST_PORT.
@EnabledIfEnvironmentVariable(named = "RABBITMQ_TEST_PORT", matches = "\\d+")
@SpringBootTest(properties = {
        "spring.rabbitmq.port=${RABBITMQ_TEST_PORT}",
        "spring.datasource.url=jdbc:h2:mem:pedido-fallback-saga;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "catalog.fallback.max-age=PT2H"
})
class CatalogFallbackSagaRabbitTests {

    private static final UUID PRODUCT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CLIENT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @MockitoBean private StockClient stockClient;
    @org.springframework.beans.factory.annotation.Autowired private OrderService orderService;
    @org.springframework.beans.factory.annotation.Autowired private CatalogProductRepository catalog;
    @org.springframework.beans.factory.annotation.Autowired private OrderRepository orders;
    @org.springframework.beans.factory.annotation.Autowired private OutboxEventRepository outbox;
    @org.springframework.beans.factory.annotation.Autowired private RabbitTemplate rabbit;
    @org.springframework.beans.factory.annotation.Autowired private OutboxRelay relay;
    @org.springframework.beans.factory.annotation.Autowired private JsonMapper jsonMapper;
    @org.springframework.beans.factory.annotation.Autowired private StockReservedListener reservedListener;
    @org.springframework.beans.factory.annotation.Autowired private StockReservationFailedListener failedListener;

    @BeforeEach
    void reset() {
        outbox.deleteAll();
        orders.deleteAll();
        catalog.deleteAll();
        rabbit.execute(channel -> { channel.queuePurge(RabbitMQConfig.STOCK_ORDER_CREATED_QUEUE); return null; });
        catalog.saveAndFlush(new CatalogProduct(PRODUCT_ID, new BigDecimal("8.00"), 0, Instant.now()));
        when(stockClient.findPrices(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new ResourceAccessException("Estoque indisponível"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void fallbackEventDrivesSagaResultAndKeepsAcceptedPrice(boolean reservationPossible) throws Exception {
        var response = orderService.createOrder(
                new OrderRequestDTO(CLIENT_ID, List.of(new OrderItemRequestDTO(PRODUCT_ID, 2))), "fallback-saga");
        var savedEvent = outbox.findAll().getFirst();
        assertThat(savedEvent.getExchange()).isEqualTo(RabbitMQConfig.ORDER_CREATED_EXCHANGE);
        relay.publishPending();
        var published = rabbit.receive(RabbitMQConfig.STOCK_ORDER_CREATED_QUEUE, 5000);
        assertThat(published).isNotNull();
        var event = jsonMapper.readTree(new String(published.getBody(), java.nio.charset.StandardCharsets.UTF_8));
        assertThat(event.get("eventId").asText()).isEqualTo(savedEvent.getEventId().toString());
        assertThat(event.get("orderId").asText()).isEqualTo(response.getOrderId().toString());
        assertThat(event.get("amount").decimalValue()).isEqualByComparingTo("16.00");
        assertThat(event.get("items").get(0).get("productId").asText()).isEqualTo(PRODUCT_ID.toString());
        assertThat(event.get("items").get(0).get("quantity").asInt()).isEqualTo(2);

        if (reservationPossible) {
            reservedListener.hearStockReserved(new StockReservedEvent(UUID.randomUUID(), response.getOrderId(),
                    Instant.now(), UUID.randomUUID(), new BigDecimal("16.00"), "BRL"));
            assertThat(orders.findById(response.getOrderId()).orElseThrow().getStatus())
                    .isEqualTo(StatusOrder.WAITING_PAYMENT);
        } else {
            failedListener.hearStockReservationFailed(new StockReservationFailedEvent(UUID.randomUUID(),
                    response.getOrderId(), Instant.now(), "Estoque insuficiente"));
            assertThat(orders.findById(response.getOrderId()).orElseThrow().getStatus())
                    .isEqualTo(StatusOrder.CANCELED);
        }
        assertThat(orders.findById(response.getOrderId()).orElseThrow().getTotalValue())
                .isEqualByComparingTo("16.00");
    }
}
