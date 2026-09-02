package com.gustavoronchi.microsservico_estoque.dto;

import java.math.BigDecimal;

public class ProductRequestDTO {

    private String name;
    private String description;
    private BigDecimal price;
    private String imgUrl;
    private Integer quantityAvailable;

    public ProductRequestDTO() {
    }

    public ProductRequestDTO(String name, String description, BigDecimal price, String imgUrl, Integer quantityAvailable) {
        this.name = name;
        this.description = description;
        this.price = price;
        this.imgUrl = imgUrl;
        this.quantityAvailable = quantityAvailable;
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
}
