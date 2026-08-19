package com.gustavoronchi.microsservico_pedido.database.repository;

import com.gustavoronchi.microsservico_pedido.database.model.OrderItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface OrderItemRepository extends JpaRepository<OrderItem, UUID> {
}
