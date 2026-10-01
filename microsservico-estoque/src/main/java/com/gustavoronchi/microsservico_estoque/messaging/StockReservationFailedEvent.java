package com.gustavoronchi.microsservico_estoque.messaging;

import java.time.Instant;
import java.util.UUID;

public class StockReservationFailedEvent {

    private UUID eventId;
    private UUID orderId;
    private Instant occurredAt;
    private String failureReason;

    public StockReservationFailedEvent() {
    }

    public StockReservationFailedEvent(UUID eventId, UUID orderId, Instant occurredAt, String failureReason) {
        this.eventId = eventId;
        this.orderId = orderId;
        this.occurredAt = occurredAt;
        this.failureReason = failureReason;
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

    public String getFailureReason() {
        return failureReason;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }
}
