package com.gustavoronchi.microsservico_pedido.domain.repository;

import com.gustavoronchi.microsservico_pedido.domain.entities.Order;
import com.gustavoronchi.microsservico_pedido.domain.entities.OrderItem;
import com.gustavoronchi.microsservico_pedido.enums.StatusOrder;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(showSql = false, properties = "spring.sql.init.mode=never")
class OrderRepositoryTests {

    @Autowired
    private OrderRepository repository;
    @Autowired
    private EntityManager entityManager;

    @Test
    void preservesTheIdUsedForStockReservationWhenSavingAndUpdating() {
        Order order = new Order();
        UUID reservationOrderId = order.getId();
        order.setClientId(UUID.randomUUID());
        order.setStatus(StatusOrder.WAITING_PAYMENT);
        order.setTotalValue(new BigDecimal("20.00"));
        order.setCreatedAt(Instant.now());
        order.setUpdatedAt(Instant.now());
        OrderItem item = new OrderItem();
        item.setOrder(order);
        item.setProductId(UUID.randomUUID());
        item.setQuantity(2);
        item.setPrice(new BigDecimal("10.00"));
        order.getItems().add(item);

        Order saved = repository.saveAndFlush(order);
        assertThat(saved.getId()).isEqualTo(reservationOrderId);
        entityManager.clear();
        Order persisted = repository.findById(reservationOrderId).orElseThrow();
        assertThat(persisted.getItems()).hasSize(1);
        assertThat(persisted.getItems().getFirst().getOrder().getId()).isEqualTo(reservationOrderId);
        persisted.setStatus(StatusOrder.PAID);
        repository.saveAndFlush(persisted);
        entityManager.clear();
        assertThat(repository.findById(reservationOrderId).orElseThrow().getStatus()).isEqualTo(StatusOrder.PAID);
    }
}
