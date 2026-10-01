package com.gustavoronchi.microsservico_estoque.dto;

import java.math.BigDecimal;
import java.util.UUID;

public class ProductPriceDTO {

    private UUID productId;
    private BigDecimal price;

    public ProductPriceDTO() {
    }

    public ProductPriceDTO(UUID productId, BigDecimal price) {
        this.productId = productId;
        this.price = price;
    }

    public UUID getProductId() {
        return productId;
    }

    public void setProductId(UUID productId) {
        this.productId = productId;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }
}
