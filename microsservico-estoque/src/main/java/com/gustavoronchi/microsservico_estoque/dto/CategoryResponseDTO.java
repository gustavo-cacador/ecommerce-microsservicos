package com.gustavoronchi.microsservico_estoque.dto;

import com.gustavoronchi.microsservico_estoque.domain.entities.Category;

import java.util.UUID;

public class CategoryResponseDTO {

    private UUID id;
    private String name;

    public CategoryResponseDTO() {
    }

    public CategoryResponseDTO(UUID id, String name) {
        this.id = id;
        this.name = name;
    }

    public CategoryResponseDTO(Category category) {
        this.id = category.getId();
        this.name = category.getName();
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
}
