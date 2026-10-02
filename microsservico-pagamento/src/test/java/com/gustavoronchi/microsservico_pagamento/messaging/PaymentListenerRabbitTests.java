package com.gustavoronchi.microsservico_pagamento.messaging;

import com.gustavoronchi.microsservico_pagamento.config.RabbitMQConfig;
import com.gustavoronchi.microsservico_pagamento.domain.repositories.PaymentRepository;
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
