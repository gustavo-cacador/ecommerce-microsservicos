package com.gustavoronchi.microsservico_estoque.domain.repository;

import com.gustavoronchi.microsservico_estoque.domain.entities.Product;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface ProductRepository extends JpaRepository<Product, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select p
            from Product p 
            where p.id = :id
             """)
    Optional<Product> findByIdForUpdate(@Param("id") UUID id);

    @Query("""
            select p
            from Product p
            where p.active = true
            and (:categoryId is null or p.categoryId = :categoryId)
            """)
    Page<Product> search(@Param("categoryId") UUID categoryId, Pageable pageable);
}
