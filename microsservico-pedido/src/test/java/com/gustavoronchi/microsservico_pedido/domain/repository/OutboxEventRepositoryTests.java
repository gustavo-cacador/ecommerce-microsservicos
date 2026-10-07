package com.gustavoronchi.microsservico_pedido.domain.repository;

import com.gustavoronchi.microsservico_pedido.domain.entities.Order;
import com.gustavoronchi.microsservico_pedido.domain.entities.OutboxEvent;
import com.gustavoronchi.microsservico_pedido.enums.StatusOrder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(showSql = false, properties = "spring.sql.init.mode=never")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OutboxEventRepositoryTests {

    @Autowired
    private OutboxEventRepository events;
    @Autowired
    private OrderRepository orders;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void commitsOrderAndEventTogetherAndKeepsPublishedEventOutOfPendingBatch() {
        Order order = order();
        OutboxEvent event = event(order);
        String payload = "{\"items\":\"" + "produto".repeat(100) + "\"}";
        event.setPayload(payload);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        transaction.executeWithoutResult(status -> {
            orders.save(order);
            events.save(event);
        });

        assertThat(orders.existsById(order.getId())).isTrue();
        transaction.executeWithoutResult(status -> {
            List<OutboxEvent> pending = events.findByPublishedAtIsNullOrderByOccurredAtAsc(PageRequest.of(0, 10));
            assertThat(pending).extracting(OutboxEvent::getEventId).contains(event.getEventId());
            OutboxEvent persisted = events.findById(event.getEventId()).orElseThrow();
            assertThat(persisted.getOrderId()).isEqualTo(order.getId());
            assertThat(persisted.getPayload()).isEqualTo(payload);
            persisted.setPublishedAt(Instant.now());
        });

        transaction.executeWithoutResult(status -> {
            assertThat(events.findByPublishedAtIsNullOrderByOccurredAtAsc(PageRequest.of(0, 10)))
                    .extracting(OutboxEvent::getEventId).doesNotContain(event.getEventId());
            assertThat(events.findById(event.getEventId()).orElseThrow().getPublishedAt()).isNotNull();
        });
    }

    @Test
    void failureAfterFlushingRollsBackBothOrderAndEvent() {
        Order order = order();
        OutboxEvent event = event(order);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            orders.saveAndFlush(order);
            events.saveAndFlush(event);
            throw new IllegalStateException("Falha antes do commit");
        })).isInstanceOf(IllegalStateException.class).hasMessage("Falha antes do commit");

        assertThat(orders.existsById(order.getId())).isFalse();
        assertThat(events.existsById(event.getEventId())).isFalse();
    }

    private Order order() {
        Order order = new Order();
        order.setClientId(java.util.UUID.randomUUID());
        order.setStatus(StatusOrder.CREATED);
        order.setTotalValue(new BigDecimal("10.00"));
        order.setCreatedAt(Instant.now());
        order.setUpdatedAt(Instant.now());
        return order;
    }

    private OutboxEvent event(Order order) {
        OutboxEvent event = new OutboxEvent();
        event.setOrderId(order.getId());
        event.setExchange("order.created");
        event.setRoutingKey("");
        event.setPayload("{\"orderId\":\"" + order.getId() + "\"}");
        event.setOccurredAt(Instant.now());
        return event;
    }
}
