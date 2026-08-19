package com.gustavoronchi.microsservico_pedido.database.repository;

import com.gustavoronchi.microsservico_pedido.database.model.Order;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface OrderRepository extends JpaRepository<Order, UUID> {
}
