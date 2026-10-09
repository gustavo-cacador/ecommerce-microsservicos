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
        "spring.datasource.url=jdbc:h2:mem:pedido-refusal-rabbit-test",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class PaymentRefusedListenerRabbitTests {

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

    @BeforeEach
    void setUp() {
        events.deleteAll();
        orders.deleteAll();
        rabbit.execute(channel -> {
            channel.queuePurge(RabbitMQConfig.ORDER_PAYMENT_REFUSED_QUEUE);
            channel.queuePurge(RabbitMQConfig.ORDER_PAYMENT_REFUSED_DLQ);
            return null;
        });
        when(stockClient.findPrices(any())).thenReturn(List.of(new ProductPriceDTO(productId, BigDecimal.TEN, 50)));
    }

    @Test
    void refusalAndRedeliveryCancelOrderWithoutPublishingCompensation() throws Exception {
        UUID orderId = createOrder();
        String refusal = refusal(orderId, "10.00");
        publish(refusal);
        await(() -> service.findById(orderId).getStatus() == StatusOrder.CANCELED);
        var updatedAt = orders.findById(orderId).orElseThrow().getUpdatedAt();

        publish(refusal);
        publish(refusal);
        publish("{}");
        await(() -> messages(RabbitMQConfig.ORDER_PAYMENT_REFUSED_DLQ) == 1);

        assertThat(service.findById(orderId).getStatus()).isEqualTo(StatusOrder.CANCELED);
        assertThat(orders.findById(orderId).orElseThrow().getUpdatedAt()).isEqualTo(updatedAt);
        assertThat(events.findAll()).singleElement()
                .satisfies(event -> assertThat(event.getExchange()).isEqualTo("order.created"));
    }

    @Test
    void invalidJsonMismatchedAmountAndUnknownOrderGoToDeadLetterWithoutCancelingAnotherOrder() throws Exception {
        UUID orderId = createOrder();
        publish("{invalid-json");
        publish(refusal(orderId, "20.00"));
        publish(refusal(UUID.randomUUID(), "10.00"));
        await(() -> messages(RabbitMQConfig.ORDER_PAYMENT_REFUSED_DLQ) == 3);

        assertThat(service.findById(orderId).getStatus()).isEqualTo(StatusOrder.CREATED);
        assertThat(orders.count()).isEqualTo(1);
        assertThat(events.count()).isEqualTo(1);
    }

    private UUID createOrder() {
        return service.createOrder(new OrderRequestDTO(UUID.randomUUID(),
                List.of(new OrderItemRequestDTO(productId, 1)))).getOrderId();
    }

    private String refusal(UUID orderId, String amount) {
        return """
                {"eventId":"%s","orderId":"%s","occurredAt":"2026-10-09T12:00:00Z",
                 "paymentId":"%s","reservationId":"%s","amount":%s,"currency":"BRL"}
                """.formatted(UUID.randomUUID(), orderId, UUID.randomUUID(), UUID.randomUUID(), amount);
    }

    private void publish(String payload) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        rabbit.send(RabbitMQConfig.PAYMENT_REFUSED_EXCHANGE, "",
                new Message(payload.getBytes(StandardCharsets.UTF_8), properties));
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
