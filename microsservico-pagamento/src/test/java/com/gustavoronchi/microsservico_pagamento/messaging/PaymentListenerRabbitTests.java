package com.gustavoronchi.microsservico_pagamento.messaging;

import com.gustavoronchi.microsservico_pagamento.config.RabbitMQConfig;
import com.gustavoronchi.microsservico_pagamento.domain.repositories.PaymentRepository;
import com.gustavoronchi.microsservico_pagamento.domain.repositories.OutboxEventRepository;
import com.gustavoronchi.microsservico_pagamento.enums.PaymentStatus;
import com.gustavoronchi.microsservico_pagamento.gateway.PaymentGateway;
import com.gustavoronchi.microsservico_pagamento.gateway.PaymentGatewayUnavailableException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

// Executar somente com um broker isolado; a aplicação declara suas próprias filas nesse broker.
@EnabledIfEnvironmentVariable(named = "RABBITMQ_TEST_PORT", matches = "\\d+")
@SpringBootTest(properties = {
        "spring.rabbitmq.port=${RABBITMQ_TEST_PORT}",
        "spring.datasource.url=jdbc:h2:mem:pagamento-rabbit-test",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class PaymentListenerRabbitTests {

    @Autowired
    private RabbitTemplate rabbit;
    @Autowired
    private PaymentRepository payments;
    @Autowired
    private OutboxEventRepository events;
    @Autowired
    private JsonMapper jsonMapper;
    @MockitoBean
    private PaymentGateway gateway;

    @Test
    void consumesRawJsonHandlesDuplicatesAndRecoversPendingFromDeadLetter() throws Exception {
        UUID orderId = UUID.randomUUID();
        String payload = """
                {"eventId":"%s","orderId":"%s","occurredAt":"2026-10-01T12:00:00Z",
                 "reservationId":"%s","amount":499.80,"currency":"BRL"}
                """.formatted(UUID.randomUUID(), orderId, UUID.randomUUID());
        doThrow(new PaymentGatewayUnavailableException("Timeout")).when(gateway).process(any(), any(), any());
        publish(payload);
        await(() -> deadLetters() == 1);
        assertThat(payments.findAll()).hasSize(1);
        assertThat(payments.findAll().getFirst().getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(events.count()).isZero();
        UUID paymentId = payments.findAll().getFirst().getId();

        doReturn(PaymentStatus.APPROVED).when(gateway).process(any(), any(), any());
        publish(payload);
        await(() -> payments.findAll().getFirst().getStatus() == PaymentStatus.APPROVED);
        publish(payload);
        publish(payload);
        await(() -> Boolean.TRUE.equals(rabbit.execute(channel -> channel.messageCount(
                        RabbitMQConfig.PAYMENT_STOCK_RESERVED_QUEUE) == 0)));
        assertThat(payments.findAll()).hasSize(1);
        assertThat(payments.findAll().getFirst().getId()).isEqualTo(paymentId);
        publish("{}");
        await(() -> deadLetters() == 2);
        assertThat(payments.findAll().getFirst().getStatus()).isEqualTo(PaymentStatus.APPROVED);
        verify(gateway, times(2)).process(orderId, payments.findAll().getFirst().getAmount(), "BRL");

        await(() -> events.count() == 1 && events.findAll().getFirst().getPublishedAt() != null);
        Message orderCopy = rabbit.receive(RabbitMQConfig.ORDER_PAYMENT_APPROVED_QUEUE, 5000);
        Message stockCopy = rabbit.receive(RabbitMQConfig.STOCK_PAYMENT_APPROVED_QUEUE, 5000);
        assertThat(orderCopy).isNotNull();
        assertThat(stockCopy).isNotNull();
        assertThat(stockCopy.getBody()).isEqualTo(orderCopy.getBody());
        var outbox = events.findAll().getFirst();
        assertThat(new String(orderCopy.getBody(), StandardCharsets.UTF_8)).isEqualTo(outbox.getPayload());
        assertThat(orderCopy.getMessageProperties().getMessageId()).isEqualTo(outbox.getEventId().toString());
        PaymentApprovedEvent approval = jsonMapper.readValue(orderCopy.getBody(), PaymentApprovedEvent.class);
        assertThat(approval.getOrderId()).isEqualTo(orderId);
        assertThat(approval.getPaymentId()).isEqualTo(paymentId);
        assertThat(approval.getReservationId()).isEqualTo(payments.findAll().getFirst().getReservationId());

        rabbit.send(RabbitMQConfig.PAYMENT_APPROVED_EXCHANGE, "", orderCopy);
        rejectResult(RabbitMQConfig.ORDER_PAYMENT_APPROVED_QUEUE);
        rejectResult(RabbitMQConfig.STOCK_PAYMENT_APPROVED_QUEUE);
        assertThat(rabbit.receive(RabbitMQConfig.ORDER_PAYMENT_APPROVED_DLQ, 5000)).isNotNull();
        assertThat(rabbit.receive(RabbitMQConfig.STOCK_PAYMENT_APPROVED_DLQ, 5000)).isNotNull();

        UUID refusedOrderId = UUID.randomUUID();
        String refusedPayload = """
                {"eventId":"%s","orderId":"%s","occurredAt":"2026-10-09T12:00:00Z",
                 "reservationId":"%s","amount":599.80,"currency":"BRL"}
                """.formatted(UUID.randomUUID(), refusedOrderId, UUID.randomUUID());
        doReturn(PaymentStatus.REFUSED).when(gateway).process(any(), any(), any());
        publish(refusedPayload);
        await(() -> events.count() == 2 && events.findAll().stream()
                .filter(event -> event.getOrderId().equals(refusedOrderId))
                .anyMatch(event -> event.getPublishedAt() != null));
        publish(refusedPayload);
        await(() -> Boolean.TRUE.equals(rabbit.execute(channel -> channel.messageCount(
                RabbitMQConfig.PAYMENT_STOCK_RESERVED_QUEUE) == 0)));
        var refusalOutbox = events.findAll().stream()
                .filter(event -> event.getOrderId().equals(refusedOrderId)).toList();
        assertThat(refusalOutbox).hasSize(1);
        assertThat(refusalOutbox.getFirst().getExchange()).isEqualTo(RabbitMQConfig.PAYMENT_REFUSED_EXCHANGE);
        Message refusedOrderCopy = rabbit.receive(RabbitMQConfig.ORDER_PAYMENT_REFUSED_QUEUE, 5000);
        Message refusedStockCopy = rabbit.receive(RabbitMQConfig.STOCK_PAYMENT_REFUSED_QUEUE, 5000);
        assertThat(refusedOrderCopy).isNotNull();
        assertThat(refusedStockCopy).isNotNull();
        assertThat(refusedStockCopy.getBody()).isEqualTo(refusedOrderCopy.getBody());
        PaymentRefusedEvent refusal = jsonMapper.readValue(refusedOrderCopy.getBody(), PaymentRefusedEvent.class);
        var refusedPayment = payments.findAll().stream()
                .filter(payment -> payment.getOrderId().equals(refusedOrderId)).findFirst().orElseThrow();
        assertThat(refusedPayment.getStatus()).isEqualTo(PaymentStatus.REFUSED);
        assertThat(refusal.getEventId()).isEqualTo(refusalOutbox.getFirst().getEventId());
        assertThat(refusal.getOrderId()).isEqualTo(refusedOrderId);
        assertThat(refusal.getPaymentId()).isEqualTo(refusedPayment.getId());
        assertThat(refusal.getReservationId()).isEqualTo(refusedPayment.getReservationId());
        verify(gateway, times(1)).process(refusedOrderId, refusedPayment.getAmount(), "BRL");

        rabbit.send(RabbitMQConfig.PAYMENT_REFUSED_EXCHANGE, "", refusedOrderCopy);
        rejectResult(RabbitMQConfig.ORDER_PAYMENT_REFUSED_QUEUE);
        rejectResult(RabbitMQConfig.STOCK_PAYMENT_REFUSED_QUEUE);
        assertThat(rabbit.receive(RabbitMQConfig.ORDER_PAYMENT_REFUSED_DLQ, 5000)).isNotNull();
        assertThat(rabbit.receive(RabbitMQConfig.STOCK_PAYMENT_REFUSED_DLQ, 5000)).isNotNull();
    }

    private void rejectResult(String queue) throws InterruptedException {
        await(() -> Boolean.TRUE.equals(rabbit.execute(channel -> channel.messageCount(queue) > 0)));
        rabbit.execute(channel -> {
            var message = channel.basicGet(queue, false);
            assertThat(message).isNotNull();
            channel.basicReject(message.getEnvelope().getDeliveryTag(), false);
            return null;
        });
    }

    private void publish(String payload) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        rabbit.send(RabbitMQConfig.STOCK_RESERVED_EXCHANGE, "",
                new Message(payload.getBytes(StandardCharsets.UTF_8), properties));
    }

    private long deadLetters() {
        Long count = rabbit.execute(channel -> channel.messageCount(RabbitMQConfig.PAYMENT_STOCK_RESERVED_DLQ));
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
