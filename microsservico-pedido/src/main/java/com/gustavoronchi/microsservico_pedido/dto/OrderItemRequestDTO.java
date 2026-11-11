package com.gustavoronchi.microsservico_pedido.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.UUID;

public class OrderItemRequestDTO {

    @NotNull
    private UUID productId;
    @NotNull
    @Positive
    private Integer quantity;

    public OrderItemRequestDTO() {
    }

    public OrderItemRequestDTO(UUID productId, Integer quantity) {
        this.productId = productId;
        this.quantity = quantity;
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
}
