package com.gustavoronchi.microsservico_pedido.service;

import com.gustavoronchi.microsservico_pedido.client.PaymentClient;
import com.gustavoronchi.microsservico_pedido.client.StockClient;
import com.gustavoronchi.microsservico_pedido.domain.entities.Order;
import com.gustavoronchi.microsservico_pedido.domain.entities.OutboxEvent;
import com.gustavoronchi.microsservico_pedido.domain.repository.OrderRepository;
import com.gustavoronchi.microsservico_pedido.domain.repository.OutboxEventRepository;
import com.gustavoronchi.microsservico_pedido.dto.*;
import com.gustavoronchi.microsservico_pedido.enums.StatusOrder;
import com.gustavoronchi.microsservico_pedido.messaging.StockActionMessage;
import com.gustavoronchi.microsservico_pedido.messaging.StockEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DataJpaTest(showSql = false, properties = "spring.sql.init.mode=never")
@Import({OrderService.class, OrderPaymentService.class, OrderPaymentServiceTests.JsonConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OrderPaymentServiceTests {

    @Autowired
    private OrderService service;
    @Autowired
    private OrderPaymentService paymentService;
    @Autowired
    private OrderRepository orders;
    @Autowired
    private JsonMapper jsonMapper;
    @MockitoSpyBean
    private OutboxEventRepository events;
    @MockitoBean
    private StockClient stockClient;
    @MockitoBean
    private PaymentClient paymentClient;
    @MockitoBean
    private StockEventPublisher publisher;

    private UUID productId;

    @BeforeEach
    void setUp() {
        events.deleteAll();
        orders.deleteAll();
        productId = UUID.randomUUID();
        StockReserveResponseDTO stock = new StockReserveResponseDTO();
        stock.setSuccess(true);
        stock.setItems(List.of(new ReservedItemDTO(productId, new BigDecimal("10.00"))));
        when(stockClient.reserve(any(), any())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return stock;
        });
        PaymentResponseDTO payment = new PaymentResponseDTO();
        payment.setStatus("APPROVED");
        when(paymentClient.process(any())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return payment;
        });
    }

    @Test
    void commitsPaidOrderAndConfirmationWithoutPublishingDirectly() {
        OrderResponseDTO response = service.createOrder(request());

        assertThat(response.getStatus()).isEqualTo(StatusOrder.PAID);
        assertThat(orders.findById(response.getOrderId()).orElseThrow().getStatus()).isEqualTo(StatusOrder.PAID);
        assertConfirmation(response.getOrderId());
        verifyNoInteractions(publisher);
    }

    @Test
    void outboxFailureRollsBackPaidStatusAndPreventsConfirmation() {
        doThrow(new DataIntegrityViolationException("Falha simulada na outbox"))
                .when(events).save(any(OutboxEvent.class));

        assertThatThrownBy(() -> service.createOrder(request())).isInstanceOf(DataIntegrityViolationException.class);

        assertThat(orders.findAll()).singleElement()
                .extracting(Order::getStatus).isEqualTo(StatusOrder.WAITING_PAYMENT);
        assertThat(events.findAll()).isEmpty();
        verifyNoInteractions(publisher);
    }

    @Test
    void repeatedApprovalDoesNotCreateAnotherEvent() {
        OrderResponseDTO response = service.createOrder(request());
        UUID eventId = events.findAll().getFirst().getEventId();

        paymentService.approvePayment(response.getOrderId());

        assertThat(events.findAll()).singleElement().extracting(OutboxEvent::getEventId).isEqualTo(eventId);
    }

    @Test
    void approvalDoesNotOverwriteCanceledOrder() {
        OrderResponseDTO response = service.createOrder(request());
        Order order = orders.findById(response.getOrderId()).orElseThrow();
        order.setStatus(StatusOrder.CANCELED);
        orders.save(order);
        long eventCount = events.count();

        assertThatThrownBy(() -> paymentService.approvePayment(order.getId()))
                .isInstanceOf(IllegalStateException.class);

        assertThat(orders.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(StatusOrder.CANCELED);
        assertThat(events.count()).isEqualTo(eventCount);
    }

    private OrderRequestDTO request() {
        return new OrderRequestDTO(UUID.randomUUID(), List.of(new OrderItemRequestDTO(productId, 2)));
    }

    private void assertConfirmation(UUID orderId) {
        assertThat(events.findAll()).hasSize(1);
        OutboxEvent event = events.findAll().getFirst();
        assertThat(event.getOrderId()).isEqualTo(orderId);
        assertThat(event.getExchange()).isEqualTo("stock.confirm");
        assertThat(event.getRoutingKey()).isEmpty();
        assertThat(event.getPublishedAt()).isNull();
        StockActionMessage message = jsonMapper.readValue(event.getPayload(), StockActionMessage.class);
        assertThat(message.getEventId()).isEqualTo(event.getEventId());
        assertThat(message.getOccurredAt()).isEqualTo(event.getOccurredAt());
        assertThat(message.getOrderId()).isEqualTo(orderId);
        assertThat(message.getItems()).singleElement().satisfies(item -> {
            assertThat(item.getProductId()).isEqualTo(productId);
            assertThat(item.getQuantity()).isEqualTo(2);
        });
    }

    @TestConfiguration
    static class JsonConfig {
        @Bean
        JsonMapper jsonMapper() {
            return JsonMapper.builder().build();
        }
    }
}
