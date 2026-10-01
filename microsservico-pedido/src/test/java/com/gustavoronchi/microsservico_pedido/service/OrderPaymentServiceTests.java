package com.gustavoronchi.microsservico_pedido.service;

import com.gustavoronchi.microsservico_pedido.domain.entities.Order;
import com.gustavoronchi.microsservico_pedido.domain.entities.OrderItem;
import com.gustavoronchi.microsservico_pedido.domain.entities.OutboxEvent;
import com.gustavoronchi.microsservico_pedido.domain.repository.OrderRepository;
import com.gustavoronchi.microsservico_pedido.domain.repository.OutboxEventRepository;
import com.gustavoronchi.microsservico_pedido.dto.OrderResponseDTO;
import com.gustavoronchi.microsservico_pedido.enums.StatusOrder;
import com.gustavoronchi.microsservico_pedido.messaging.StockActionMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

@DataJpaTest(showSql = false, properties = "spring.sql.init.mode=never")
@Import({OrderPaymentService.class, OrderPaymentServiceTests.JsonConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OrderPaymentServiceTests {

    @Autowired
    private OrderPaymentService paymentService;
    @Autowired
    private OrderRepository orders;
    @Autowired
    private JsonMapper jsonMapper;
    @MockitoSpyBean
    private OutboxEventRepository events;

    @BeforeEach
    void setUp() {
        events.deleteAll();
        orders.deleteAll();
    }

    @Test
    void commitsPaidOrderAndConfirmationTogether() {
        Order order = waitingOrder();

        OrderResponseDTO response = paymentService.approvePayment(order.getId());

        assertThat(response.getStatus()).isEqualTo(StatusOrder.PAID);
        assertThat(orders.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(StatusOrder.PAID);
        assertThat(events.findAll()).hasSize(1);
        OutboxEvent event = events.findAll().getFirst();
        assertThat(event.getOrderId()).isEqualTo(order.getId());
        assertThat(event.getExchange()).isEqualTo("stock.confirm");
        assertThat(event.getPublishedAt()).isNull();
        StockActionMessage message = jsonMapper.readValue(event.getPayload(), StockActionMessage.class);
        assertThat(message.getEventId()).isEqualTo(event.getEventId());
        assertThat(message.getOrderId()).isEqualTo(order.getId());
        assertThat(message.getItems()).singleElement().satisfies(item -> assertThat(item.getQuantity()).isEqualTo(2));
    }

    @Test
    void outboxFailureRollsBackPaidStatus() {
        Order order = waitingOrder();
        doThrow(new DataIntegrityViolationException("Falha simulada na outbox"))
                .when(events).save(any(OutboxEvent.class));

        assertThatThrownBy(() -> paymentService.approvePayment(order.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(orders.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(StatusOrder.WAITING_PAYMENT);
        assertThat(events.findAll()).isEmpty();
    }

    @Test
    void repeatedApprovalDoesNotCreateAnotherEvent() {
        Order order = waitingOrder();
        paymentService.approvePayment(order.getId());
        UUID eventId = events.findAll().getFirst().getEventId();

        paymentService.approvePayment(order.getId());

        assertThat(events.findAll()).singleElement().extracting(OutboxEvent::getEventId).isEqualTo(eventId);
    }

    @Test
    void approvalDoesNotOverwriteCanceledOrder() {
        Order order = waitingOrder();
        order.setStatus(StatusOrder.CANCELED);
        orders.save(order);

        assertThatThrownBy(() -> paymentService.approvePayment(order.getId()))
                .isInstanceOf(IllegalStateException.class);

        assertThat(orders.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(StatusOrder.CANCELED);
        assertThat(events.findAll()).isEmpty();
    }

    private Order waitingOrder() {
        Order order = new Order();
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
        return orders.saveAndFlush(order);
    }

    @TestConfiguration
    static class JsonConfig {
        @Bean
        JsonMapper jsonMapper() {
            return JsonMapper.builder().build();
        }
    }
}
