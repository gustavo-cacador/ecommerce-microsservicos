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
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
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
    @MockitoSpyBean
    private PaymentListener listener;

    @Test
    void consumesRawJsonHandlesDuplicatesAndRecoversPendingFromDeadLetter() throws Exception {
        String otherSubscriberQueue = rabbit.execute(channel -> {
            String queue = channel.queueDeclare("", false, true, false, null).getQueue();
            channel.queueBind(queue, RabbitMQConfig.STOCK_RESERVED_EXCHANGE, "");
            return queue;
        });
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
        verify(gateway, times(3)).process(orderId, payments.findAll().getFirst().getAmount(), "BRL");
        UUID paymentId = payments.findAll().getFirst().getId();
        assertThat(rabbit.receive(otherSubscriberQueue, 5000)).isNotNull();

        Message recovery = peekDeadLetter();
        StockReservedEvent original = jsonMapper.readValue(recovery.getBody(), StockReservedEvent.class);
        assertThat(original.getOrderId()).isEqualTo(orderId);
        assertThat(new String(recovery.getBody(), StandardCharsets.UTF_8)).isEqualTo(payload);
        assertThat(recovery.getMessageProperties().getMessageId()).isEqualTo(original.getEventId().toString());
        assertThat(deadLetters()).isEqualTo(1);

        doReturn(PaymentStatus.APPROVED).when(gateway).process(any(), any(), any());
        rabbit.send("", RabbitMQConfig.PAYMENT_STOCK_RESERVED_QUEUE, recovery);
        await(() -> payments.findAll().getFirst().getStatus() == PaymentStatus.APPROVED);
        assertThat(rabbit.receive(otherSubscriberQueue)).isNull();
        assertThat(deadLetters()).isEqualTo(1);

        // A cópia original só é removida depois de confirmar o resultado persistido.
        Message removed = rabbit.receive(RabbitMQConfig.PAYMENT_STOCK_RESERVED_DLQ, 5000);
        assertThat(removed).isNotNull();
        assertThat(removed.getBody()).isEqualTo(recovery.getBody());
        assertThat(deadLetters()).isZero();
        rabbit.send("", RabbitMQConfig.PAYMENT_STOCK_RESERVED_QUEUE, recovery);
        rabbit.send("", RabbitMQConfig.PAYMENT_STOCK_RESERVED_QUEUE, recovery);
        await(() -> Boolean.TRUE.equals(rabbit.execute(channel -> channel.messageCount(
                        RabbitMQConfig.PAYMENT_STOCK_RESERVED_QUEUE) == 0)));
        assertThat(payments.findAll()).hasSize(1);
        assertThat(payments.findAll().getFirst().getId()).isEqualTo(paymentId);
        publish("{}");
        await(() -> deadLetters() == 1);
        assertThat(payments.findAll().getFirst().getStatus()).isEqualTo(PaymentStatus.APPROVED);
        verify(listener, times(1)).hearStockReserved(argThat(event -> event != null && event.getEventId() == null));
        verify(gateway, times(4)).process(orderId, payments.findAll().getFirst().getAmount(), "BRL");

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

        UUID recoveredOrderId = UUID.randomUUID();
        String recoveredPayload = """
                {"eventId":"%s","orderId":"%s","occurredAt":"2026-10-18T12:00:00Z",
                 "reservationId":"%s","amount":100.00,"currency":"BRL"}
                """.formatted(UUID.randomUUID(), recoveredOrderId, UUID.randomUUID());
        var attempts = new CopyOnWriteArrayList<Long>();
        doAnswer(invocation -> {
            attempts.add(System.nanoTime());
            if (attempts.size() < 3) {
                throw new PaymentGatewayUnavailableException("Indisponibilidade temporária");
            }
            return PaymentStatus.APPROVED;
        }).when(gateway).process(eq(recoveredOrderId), any(), eq("BRL"));

        publish(recoveredPayload);
        await(() -> payments.findAll().stream().anyMatch(payment -> payment.getOrderId().equals(recoveredOrderId)
                && payment.getStatus() == PaymentStatus.APPROVED));
        assertThat(attempts).hasSize(3);
        assertThat(TimeUnit.NANOSECONDS.toMillis(attempts.get(1) - attempts.get(0))).isGreaterThanOrEqualTo(1000);
        assertThat(TimeUnit.NANOSECONDS.toMillis(attempts.get(2) - attempts.get(1))).isGreaterThanOrEqualTo(2000);
        assertThat(payments.count()).isEqualTo(3);
        assertThat(events.findAll().stream().filter(event -> event.getOrderId().equals(recoveredOrderId)).count())
                .isEqualTo(1);
        assertThat(deadLetters()).isEqualTo(1);

        publish(recoveredPayload);
        // A mensagem inválida serve também de marcador após a duplicata, no mesmo consumidor.
        publish("{}");
        await(() -> deadLetters() == 2);
        verify(gateway, times(3)).process(eq(recoveredOrderId), any(), eq("BRL"));
        assertThat(payments.count()).isEqualTo(3);
        assertThat(events.count()).isEqualTo(3);
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
        StockReservedEvent event = jsonMapper.readValue(payload, StockReservedEvent.class);
        if (event.getEventId() != null) {
            properties.setMessageId(event.getEventId().toString());
        }
        rabbit.send(RabbitMQConfig.STOCK_RESERVED_EXCHANGE, "",
                new Message(payload.getBytes(StandardCharsets.UTF_8), properties));
    }

    private Message peekDeadLetter() {
        return rabbit.execute(channel -> {
            var original = channel.basicGet(RabbitMQConfig.PAYMENT_STOCK_RESERVED_DLQ, false);
            assertThat(original).isNotNull();
            channel.basicNack(original.getEnvelope().getDeliveryTag(), false, true);
            // Como no painel: preservar o payload e o message_id, sem copiar os headers de dead-letter.
            MessageProperties properties = new MessageProperties();
            properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
            properties.setContentEncoding(StandardCharsets.UTF_8.name());
            properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            properties.setMessageId(original.getProps().getMessageId());
            return new Message(original.getBody(), properties);
        });
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
