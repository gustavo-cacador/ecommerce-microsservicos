package com.gustavoronchi.microsservico_pedido.dto;

import com.gustavoronchi.microsservico_pedido.enums.StatusOrder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class OrderResponseDTO {

    private UUID orderId;
    private UUID clientId;
    private StatusOrder status;
    private BigDecimal totalValue;
    private Instant createdAt;
    private List<OrderItemResponseDTO> items;

    public OrderResponseDTO() {
    }

    public OrderResponseDTO(UUID orderId, UUID clientId, StatusOrder status, BigDecimal totalValue, Instant createdAt, List<OrderItemResponseDTO> items) {
        this.orderId = orderId;
        this.clientId = clientId;
        this.status = status;
        this.totalValue = totalValue;
        this.createdAt = createdAt;
        this.items = items;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public void setOrderId(UUID orderId) {
        this.orderId = orderId;
    }

    public UUID getClientId() {
        return clientId;
    }

    public void setClientId(UUID clientId) {
        this.clientId = clientId;
    }

    public StatusOrder getStatus() {
        return status;
    }

    public void setStatus(StatusOrder status) {
        this.status = status;
    }

    public BigDecimal getTotalValue() {
        return totalValue;
    }

    public void setTotalValue(BigDecimal totalValue) {
        this.totalValue = totalValue;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public List<OrderItemResponseDTO> getItems() {
        return items;
    }

    public void setItems(List<OrderItemResponseDTO> items) {
        this.items = items;
    }
}
