package com.gustavoronchi.microsservico_estoque.domain.repository;

import com.gustavoronchi.microsservico_estoque.domain.entities.Product;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ProductRepository extends JpaRepository<Product, UUID> {
}
