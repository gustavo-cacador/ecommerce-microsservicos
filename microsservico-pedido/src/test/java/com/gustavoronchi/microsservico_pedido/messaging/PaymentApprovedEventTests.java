package com.gustavoronchi.microsservico_pedido.messaging;

import com.gustavoronchi.microsservico_pedido.config.RabbitMQConfig;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentApprovedEventTests {

    @Test
    void convertsPaymentJsonWithoutJavaTypeHeaders() {
        String json = """
                {
                  "eventId": "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                  "orderId": "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
                  "occurredAt": "2026-10-05T12:00:00Z",
                  "paymentId": "cccccccc-cccc-4ccc-8ccc-cccccccccccc",
                  "reservationId": "dddddddd-dddd-4ddd-8ddd-dddddddddddd",
                  "amount": 20.00,
                  "currency": "BRL"
                }
                """;
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setInferredArgumentType(PaymentApprovedEvent.class);

        Object converted = new RabbitMQConfig().jsonMessageConverter()
                .fromMessage(new Message(json.getBytes(StandardCharsets.UTF_8), properties));

        assertThat(converted).isInstanceOf(PaymentApprovedEvent.class);
        PaymentApprovedEvent event = (PaymentApprovedEvent) converted;
        assertThat(event.getEventId().toString()).isEqualTo("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
        assertThat(event.getOrderId().toString()).isEqualTo("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
        assertThat(event.getOccurredAt()).isEqualTo(Instant.parse("2026-10-05T12:00:00Z"));
        assertThat(event.getPaymentId().toString()).isEqualTo("cccccccc-cccc-4ccc-8ccc-cccccccccccc");
        assertThat(event.getReservationId().toString()).isEqualTo("dddddddd-dddd-4ddd-8ddd-dddddddddddd");
        assertThat(event.getAmount()).isEqualByComparingTo("20.00");
        assertThat(event.getCurrency()).isEqualTo("BRL");
    }
}
