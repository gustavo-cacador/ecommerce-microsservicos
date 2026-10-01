package com.gustavoronchi.microsservico_pedido.messaging;

import com.gustavoronchi.microsservico_pedido.dto.StockItemRequestDTO;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OrderCreatedEventTests {

    @Test
    void serializesTheContractExpectedByStock() {
        OrderCreatedEvent event = new OrderCreatedEvent(
                UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
                UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
                Instant.parse("2026-09-30T12:00:00Z"),
                List.of(new StockItemRequestDTO(UUID.fromString("11111111-1111-1111-1111-111111111111"), 2)),
                new BigDecimal("200.00"), "BRL");
        String expected = """
                {
                  "eventId": "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                  "orderId": "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
                  "occurredAt": "2026-09-30T12:00:00Z",
                  "items": [{"productId": "11111111-1111-1111-1111-111111111111", "quantity": 2}],
                  "amount": 200.00,
                  "currency": "BRL"
                }
                """;
        JsonMapper mapper = JsonMapper.builder().build();

        assertThat(mapper.readTree(mapper.writeValueAsString(event))).isEqualTo(mapper.readTree(expected));
    }
}
