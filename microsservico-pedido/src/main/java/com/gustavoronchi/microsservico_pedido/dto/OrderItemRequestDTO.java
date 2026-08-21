package com.gustavoronchi.microsservico_pedido.dto;

import java.util.UUID;

public class OrderItemRequestDTO {

    private UUID productId;
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
