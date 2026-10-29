package com.gustavoronchi.microsservico_pedido.domain.repository;

import com.gustavoronchi.microsservico_pedido.domain.entities.CatalogProduct;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface CatalogProductRepository extends JpaRepository<CatalogProduct, UUID> {
}
