package com.gustavoronchi.microsservico_pedido.domain.entities;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "catalog_products")
public class CatalogProduct {

    @Id
    private UUID productId;

    @Column(nullable = false)
    private BigDecimal price;

    @Column(nullable = false)
    private Integer availableStock;

    @Column(nullable = false)
    private Instant updatedAt;

    public CatalogProduct() {
    }

    public CatalogProduct(UUID productId, BigDecimal price, Integer availableStock, Instant updatedAt) {
        this.productId = productId;
        this.price = price;
        this.availableStock = availableStock;
        this.updatedAt = updatedAt;
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

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
