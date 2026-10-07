package com.gustavoronchi.microsservico_estoque.messaging;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public class PaymentApprovedEvent {

    private UUID eventId;
    private UUID orderId;
    private Instant occurredAt;
    private UUID paymentId;
    private UUID reservationId;
    private BigDecimal amount;
    private String currency;

    public PaymentApprovedEvent() {
    }

    public PaymentApprovedEvent(UUID eventId, UUID orderId, Instant occurredAt, UUID paymentId, UUID reservationId, BigDecimal amount, String currency) {
        this.eventId = eventId;
        this.orderId = orderId;
        this.occurredAt = occurredAt;
        this.paymentId = paymentId;
        this.reservationId = reservationId;
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

    public UUID getPaymentId() {
        return paymentId;
    }

    public void setPaymentId(UUID paymentId) {
        this.paymentId = paymentId;
    }

    public UUID getReservationId() {
        return reservationId;
    }

    public void setReservationId(UUID reservationId) {
        this.reservationId = reservationId;
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

