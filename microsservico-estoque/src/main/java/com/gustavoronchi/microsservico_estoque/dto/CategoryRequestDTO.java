package com.gustavoronchi.microsservico_estoque.dto;

import com.gustavoronchi.microsservico_estoque.domain.entities.Category;

public class CategoryRequestDTO {

    private String name;

    public CategoryRequestDTO() {
    }

    public CategoryRequestDTO(String name) {
        this.name = name;
    }

    public CategoryRequestDTO(Category category) {
        this.name = category.getName();
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
