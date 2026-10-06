package com.gustavoronchi.microsservico_pedido.messaging;

import com.gustavoronchi.microsservico_pedido.client.StockClient;
import com.gustavoronchi.microsservico_pedido.config.RabbitMQConfig;
import com.gustavoronchi.microsservico_pedido.domain.repository.OrderRepository;
import com.gustavoronchi.microsservico_pedido.domain.repository.OutboxEventRepository;
import com.gustavoronchi.microsservico_pedido.dto.OrderItemRequestDTO;
import com.gustavoronchi.microsservico_pedido.dto.OrderRequestDTO;
import com.gustavoronchi.microsservico_pedido.dto.ProductPriceDTO;
import com.gustavoronchi.microsservico_pedido.enums.StatusOrder;
import com.gustavoronchi.microsservico_pedido.service.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

// Executar somente com um broker isolado: as filas desse teste são declaradas e limpas nesse broker.
@EnabledIfEnvironmentVariable(named = "RABBITMQ_TEST_PORT", matches = "\\d+")
@SpringBootTest(properties = {
        "spring.rabbitmq.port=${RABBITMQ_TEST_PORT}",
        "spring.datasource.url=jdbc:h2:mem:pedido-approval-rabbit-test",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class PaymentApprovedListenerRabbitTests {

    @Autowired
    private RabbitTemplate rabbit;
    @Autowired
    private OrderService service;
    @Autowired
    private OrderRepository orders;
    @Autowired
    private OutboxEventRepository events;
    @MockitoBean
    private StockClient stockClient;
    @MockitoBean
    private OutboxRelay outboxRelay;
    private final UUID productId = UUID.randomUUID();
    private final UUID reservationId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        events.deleteAll();
        orders.deleteAll();
        rabbit.execute(channel -> {
            for (String queue : List.of(RabbitMQConfig.ORDER_PAYMENT_APPROVED_QUEUE,
                    RabbitMQConfig.ORDER_PAYMENT_APPROVED_DLQ, RabbitMQConfig.ORDER_STOCK_RESERVED_QUEUE,
                    RabbitMQConfig.ORDER_STOCK_RESERVED_DLQ)) {
                channel.queuePurge(queue);
            }
            return null;
        });
        when(stockClient.findPrices(any())).thenReturn(List.of(new ProductPriceDTO(productId, new BigDecimal("10.00"))));
    }

    @Test
    void approvalBeforeReservationAndRepeatedMessagesKeepPaidWithoutStockConfirmation() throws Exception {
        UUID orderId = createOrder();
        String approval = approval(orderId, "10.00");
        publish(RabbitMQConfig.PAYMENT_APPROVED_EXCHANGE, approval);
        await(() -> service.findById(orderId).getStatus() == StatusOrder.PAID);
        var updatedAt = orders.findById(orderId).orElseThrow().getUpdatedAt();

        publish(RabbitMQConfig.PAYMENT_APPROVED_EXCHANGE, approval);
        publish(RabbitMQConfig.PAYMENT_APPROVED_EXCHANGE, approval);
        publish(RabbitMQConfig.PAYMENT_APPROVED_EXCHANGE, "{}");
        await(() -> messages(RabbitMQConfig.ORDER_PAYMENT_APPROVED_DLQ) == 1);

        String reservation = """
                {"eventId":"%s","orderId":"%s","occurredAt":"2026-10-05T12:00:00Z",
                 "reservationId":"%s","amount":10.00,"currency":"BRL"}
                """.formatted(UUID.randomUUID(), orderId, reservationId);
        publish(RabbitMQConfig.STOCK_RESERVED_EXCHANGE, reservation);
        publish(RabbitMQConfig.STOCK_RESERVED_EXCHANGE, "{}");
        await(() -> messages(RabbitMQConfig.ORDER_STOCK_RESERVED_DLQ) == 1);

        assertThat(service.findById(orderId).getStatus()).isEqualTo(StatusOrder.PAID);
        assertThat(orders.findById(orderId).orElseThrow().getUpdatedAt()).isEqualTo(updatedAt);
        assertThat(events.findAll()).hasSize(1);
        assertThat(events.findAll().getFirst().getExchange()).isEqualTo("order.created");
    }

    @Test
    void wrongAmountAndUnknownOrderGoToDeadLetterWithoutChangingOrders() throws Exception {
        UUID orderId = createOrder();
        publish(RabbitMQConfig.PAYMENT_APPROVED_EXCHANGE, approval(orderId, "99.00"));
        publish(RabbitMQConfig.PAYMENT_APPROVED_EXCHANGE, approval(UUID.randomUUID(), "10.00"));
        await(() -> messages(RabbitMQConfig.ORDER_PAYMENT_APPROVED_DLQ) == 2);

        assertThat(service.findById(orderId).getStatus()).isEqualTo(StatusOrder.CREATED);
        assertThat(orders.count()).isEqualTo(1);
        assertThat(events.count()).isEqualTo(1);
    }

    private UUID createOrder() {
        return service.createOrder(new OrderRequestDTO(UUID.randomUUID(),
                List.of(new OrderItemRequestDTO(productId, 1)))).getOrderId();
    }

    private String approval(UUID orderId, String amount) {
        return """
                {"eventId":"%s","orderId":"%s","occurredAt":"2026-10-05T12:00:00Z",
                 "paymentId":"%s","reservationId":"%s","amount":%s,"currency":"BRL"}
                """.formatted(UUID.randomUUID(), orderId, UUID.randomUUID(), reservationId, amount);
    }

    private void publish(String exchange, String payload) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        rabbit.send(exchange, "", new Message(payload.getBytes(StandardCharsets.UTF_8), properties));
    }

    private long messages(String queue) {
        Long count = rabbit.execute(channel -> channel.messageCount(queue));
        return count == null ? 0 : count;
    }

    private void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }
}
