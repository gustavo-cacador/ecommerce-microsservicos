package com.gustavoronchi.microsservico_estoque.domain.repository;

import com.gustavoronchi.microsservico_estoque.domain.entities.StockReservation;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface StockReservationRepository extends JpaRepository<StockReservation, UUID> {

    Optional<StockReservation> findByOrderId(UUID orderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select r
            from StockReservation r
            where r.orderId = :orderId
            """)
    Optional<StockReservation> findByOrderIdForUpdate(@Param("orderId") UUID orderId);
}
