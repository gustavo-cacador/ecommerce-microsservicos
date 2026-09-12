package com.gustavoronchi.microsservico_estoque.domain.repository;

import com.gustavoronchi.microsservico_estoque.domain.entities.Category;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface CategoryRepository extends JpaRepository<Category, UUID> {
}
