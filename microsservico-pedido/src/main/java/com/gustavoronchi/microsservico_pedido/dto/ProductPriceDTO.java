package com.gustavoronchi.microsservico_pedido.dto;

import java.math.BigDecimal;
import java.util.UUID;

public class ProductPriceDTO {

    private UUID productId;
    private BigDecimal price;
    private Integer availableStock;

    public ProductPriceDTO() {
    }

    public ProductPriceDTO(UUID productId, BigDecimal price, Integer availableStock) {
        this.productId = productId;
        this.price = price;
        this.availableStock = availableStock;
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

    public Integer getAvailableStock() {
        return availableStock;
    }

    public void setAvailableStock(Integer availableStock) {
        this.availableStock = availableStock;
    }
}
