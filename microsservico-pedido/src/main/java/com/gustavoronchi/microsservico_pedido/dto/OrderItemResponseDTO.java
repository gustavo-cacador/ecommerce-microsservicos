package com.gustavoronchi.microsservico_pedido.dto;

import java.math.BigDecimal;
import java.util.UUID;

public class OrderItemResponseDTO {

    private UUID productId;
    private Integer quantity;
    private BigDecimal unitValue;
    private BigDecimal totalValue;

    public OrderItemResponseDTO() {
}

    public OrderItemResponseDTO(UUID productId, Integer quantity, BigDecimal unitValue, BigDecimal totalValue) {
        this.productId = productId;
        this.quantity = quantity;
        this.unitValue = unitValue;
        this.totalValue = totalValue;
    }

    public UUID getProductId() {
        return productId;
    }

    public void setProductId(UUID productId) {
        this.productId = productId;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public void setQuantity(Integer quantity) {
        this.quantity = quantity;
    }

    public BigDecimal getUnitValue() {
        return unitValue;
    }

    public void setUnitValue(BigDecimal unitValue) {
        this.unitValue = unitValue;
    }

    public BigDecimal getTotalValue() {
        return totalValue;
    }

    public void setTotalValue(BigDecimal totalValue) {
        this.totalValue = totalValue;
    }
}
