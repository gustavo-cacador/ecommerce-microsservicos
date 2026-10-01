package com.gustavoronchi.microsservico_estoque.messaging;

import com.gustavoronchi.microsservico_estoque.dto.StockItemRequestDTO;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class OrderCreatedEvent {

    private UUID eventId;
    private UUID orderId;
    private Instant occurredAt;
    private List<StockItemRequestDTO> items;
    private BigDecimal amount;
    private String currency;

    public OrderCreatedEvent() {
    }

    public OrderCreatedEvent(UUID eventId, UUID orderId, Instant occurredAt,
                             List<StockItemRequestDTO> items, BigDecimal amount, String currency) {
        this.eventId = eventId;
        this.orderId = orderId;
        this.occurredAt = occurredAt;
        this.items = items;
        this.amount = amount;
        this.currency = currency;
    }

    public UUID getEventId() {
        return eventId;
    }

    public void setEventId(UUID eventId) {
        this.eventId = eventId;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public void setOrderId(UUID orderId) {
        this.orderId = orderId;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(Instant occurredAt) {
        this.occurredAt = occurredAt;
    }

    public List<StockItemRequestDTO> getItems() {
        return items;
    }

    public void setItems(List<StockItemRequestDTO> items) {
        this.items = items;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }
}
