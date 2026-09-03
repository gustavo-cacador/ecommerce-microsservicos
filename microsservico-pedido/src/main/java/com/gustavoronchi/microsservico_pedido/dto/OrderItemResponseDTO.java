package com.gustavoronchi.microsservico_pedido.dto;

import com.gustavoronchi.microsservico_pedido.domain.entities.OrderItem;

import java.math.BigDecimal;
import java.util.UUID;

public class OrderItemResponseDTO {

    private UUID productId;
    private Integer quantity;
    private BigDecimal unitValue;
    private BigDecimal totalValue;

    public OrderItemResponseDTO() {
}

    public OrderItemResponseDTO(UUID productId, Integer quantity, BigDecimal unitValue, BigDecimal totalValue, BigDecimal price) {
        this.productId = productId;
        this.quantity = quantity;
        this.unitValue = unitValue;
        this.totalValue = totalValue;
    }

    public OrderItemResponseDTO(OrderItem item) {
        this.productId = item.getProductId();
        this.quantity = item.getQuantity();
        this.totalValue = item.getPrice().multiply(BigDecimal.valueOf(item.getQuantity()));
        this.unitValue = item.getPrice();
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
