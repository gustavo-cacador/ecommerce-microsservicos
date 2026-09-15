package com.gustavoronchi.microsservico_pedido.dto;

import java.math.BigDecimal;
import java.util.UUID;

public class ProductResponseDTO {

    private UUID id;
    private BigDecimal price;

    public ProductResponseDTO() {
    }

    public ProductResponseDTO(UUID id, BigDecimal price) {
        this.id = id;
        this.price = price;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }
}
