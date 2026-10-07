package com.gustavoronchi.microsservico_pedido.messaging;

import com.gustavoronchi.microsservico_pedido.config.RabbitMQConfig;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class StockReservationFailedEventTests {

    @Test
    void convertsStockJsonWithoutJavaTypeHeaders() {
        String json = """
                {"eventId":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                 "orderId":"bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
                 "occurredAt":"2026-10-06T12:00:00Z",
                 "failureReason":"Estoque insuficiente para o produto Teclado"}
                """;
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setInferredArgumentType(StockReservationFailedEvent.class);

        Object converted = new RabbitMQConfig().jsonMessageConverter()
                .fromMessage(new Message(json.getBytes(StandardCharsets.UTF_8), properties));

        assertThat(converted).isInstanceOf(StockReservationFailedEvent.class);
        StockReservationFailedEvent event = (StockReservationFailedEvent) converted;
        assertThat(event.getEventId().toString()).isEqualTo("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
        assertThat(event.getOrderId().toString()).isEqualTo("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
        assertThat(event.getOccurredAt()).isEqualTo(Instant.parse("2026-10-06T12:00:00Z"));
        assertThat(event.getFailureReason()).isEqualTo("Estoque insuficiente para o produto Teclado");
    }
}
