package com.gustavoronchi.microsservico_estoque.dto;

import com.gustavoronchi.microsservico_estoque.domain.entities.Product;

import java.math.BigDecimal;
import java.util.UUID;

public class ProductResponseDTO {

    private UUID id;
    private String name;
    private String description;
    private BigDecimal price;
    private String imgUrl;
    private Integer quantityAvailable;
    private Integer quantityReserved;

    public ProductResponseDTO() {
    }

    public ProductResponseDTO(UUID id, String name, String description, BigDecimal price, String imgUrl, Integer quantityAvailable, Integer quantityReserved) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.price = price;
        this.imgUrl = imgUrl;
        this.quantityAvailable = quantityAvailable;
        this.quantityReserved = quantityReserved;
    }

    public ProductResponseDTO(Product product) {
        this.id = product.getId();
        this.name = product.getName();
        this.description = product.getDescription();
        this.price = product.getPrice();
        this.imgUrl = product.getImgUrl();
        this.quantityAvailable = product.getQuantityAvailable();
        this.quantityReserved = product.getQuantityReserved();
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public String getImgUrl() {
        return imgUrl;
    }

    public void setImgUrl(String imgUrl) {
        this.imgUrl = imgUrl;
    }

    public Integer getQuantityAvailable() {
        return quantityAvailable;
    }

    public void setQuantityAvailable(Integer quantityAvailable) {
        this.quantityAvailable = quantityAvailable;
    }

    public Integer getQuantityReserved() {
        return quantityReserved;
    }

    public void setQuantityReserved(Integer quantityReserved) {
        this.quantityReserved = quantityReserved;
    }
}
