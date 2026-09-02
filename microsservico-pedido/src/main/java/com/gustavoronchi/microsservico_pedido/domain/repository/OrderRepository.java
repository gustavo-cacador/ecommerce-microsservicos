package com.gustavoronchi.microsservico_pedido.domain.repository;

import com.gustavoronchi.microsservico_pedido.domain.entities.Order;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface OrderRepository extends JpaRepository<Order, UUID> {
}
