package com.gustavoronchi.microsservico_pedido.domain.repository;

import com.gustavoronchi.microsservico_pedido.domain.entities.OrderItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface OrderItemRepository extends JpaRepository<OrderItem, UUID> {
}
