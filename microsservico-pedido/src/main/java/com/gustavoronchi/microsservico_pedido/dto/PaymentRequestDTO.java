package com.gustavoronchi.microsservico_pedido.dto;

import java.math.BigDecimal;
import java.util.UUID;

public class PaymentRequestDTO {

    private UUID orderId;
    private BigDecimal amount;

    public PaymentRequestDTO() {
    }

    public PaymentRequestDTO(UUID orderId, BigDecimal amount) {
        this.orderId = orderId;
        this.amount = amount;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public void setOrderId(UUID orderId) {
        this.orderId = orderId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }
}
