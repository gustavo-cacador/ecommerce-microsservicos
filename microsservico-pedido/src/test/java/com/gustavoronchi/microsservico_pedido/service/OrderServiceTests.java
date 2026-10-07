package com.gustavoronchi.microsservico_pedido.service;

import com.gustavoronchi.microsservico_pedido.client.StockClient;
import com.gustavoronchi.microsservico_pedido.domain.entities.OutboxEvent;
import com.gustavoronchi.microsservico_pedido.domain.repository.OrderRepository;
import com.gustavoronchi.microsservico_pedido.domain.repository.OutboxEventRepository;
import com.gustavoronchi.microsservico_pedido.dto.OrderItemRequestDTO;
import com.gustavoronchi.microsservico_pedido.dto.OrderRequestDTO;
import com.gustavoronchi.microsservico_pedido.dto.ProductPriceDTO;
import com.gustavoronchi.microsservico_pedido.dto.StockItemRequestDTO;
import com.gustavoronchi.microsservico_pedido.enums.StatusOrder;
import com.gustavoronchi.microsservico_pedido.exception.InvalidOrderRequestException;
import com.gustavoronchi.microsservico_pedido.exception.OrderNotFoundException;
import com.gustavoronchi.microsservico_pedido.messaging.OrderCreatedEvent;
import com.gustavoronchi.microsservico_pedido.messaging.PaymentApprovedEvent;
import com.gustavoronchi.microsservico_pedido.messaging.PaymentApprovedListener;
import com.gustavoronchi.microsservico_pedido.messaging.StockReservedEvent;
import com.gustavoronchi.microsservico_pedido.messaging.StockReservedListener;
import com.gustavoronchi.microsservico_pedido.messaging.StockReservationFailedEvent;
import com.gustavoronchi.microsservico_pedido.messaging.StockReservationFailedListener;
import com.gustavoronchi.microsservico_pedido.resource.OrderResource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DataJpaTest(showSql = false, properties = "spring.sql.init.mode=never")
@Import({OrderService.class, StockReservedListener.class,
        PaymentApprovedListener.class, StockReservationFailedListener.class, OrderServiceTests.JsonConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OrderServiceTests {

    @Autowired
    private OrderService orderService;
    @Autowired
    private OrderRepository orders;
    @Autowired
    private JsonMapper jsonMapper;
    @MockitoSpyBean
    private OutboxEventRepository events;
    @MockitoBean
    private StockClient stockClient;
    @Autowired
    private StockReservedListener stockReservedListener;
    @Autowired
    private PaymentApprovedListener paymentApprovedListener;
    @Autowired
    private StockReservationFailedListener stockReservationFailedListener;

    private MockMvc mvc;
    private final UUID firstProductId = UUID.randomUUID();
    private final UUID secondProductId = UUID.randomUUID();
    private ProductPriceDTO firstPrice;

    @BeforeEach
    void setUp() {
        events.deleteAll();
        orders.deleteAll();
        firstPrice = new ProductPriceDTO(firstProductId, new BigDecimal("10.00"), 50);
        when(stockClient.findPrices(any())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return List.of(firstPrice, new ProductPriceDTO(secondProductId, new BigDecimal("20.00"), 50));
        });
        mvc = MockMvcBuilders.standaloneSetup(new OrderResource(orderService)).build();
    }

    @Test
    void createsOrderAndOutboxWithServerPrices() throws Exception {
        OrderRequestDTO request = new OrderRequestDTO(UUID.randomUUID(), List.of(
                new OrderItemRequestDTO(firstProductId, 2),
                new OrderItemRequestDTO(secondProductId, 1),
                new OrderItemRequestDTO(firstProductId, 3)));

        var response = mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.totalValue").value(70.00))
                .andReturn().getResponse();

        UUID orderId = UUID.fromString(jsonMapper.readTree(response.getContentAsString()).get("orderId").asString());
        assertThat(response.getHeader("Location")).endsWith("/orders/" + orderId);
        verify(stockClient).findPrices(List.of(firstProductId, secondProductId));
        assertThat(events.findAll()).hasSize(1);
        OutboxEvent event = events.findAll().getFirst();
        assertThat(event.getOrderId()).isEqualTo(orderId);
        assertThat(event.getExchange()).isEqualTo("order.created");
        assertThat(event.getPublishedAt()).isNull();
        OrderCreatedEvent message = jsonMapper.readValue(event.getPayload(), OrderCreatedEvent.class);
        assertThat(message.getEventId()).isEqualTo(event.getEventId());
        assertThat(message.getOrderId()).isEqualTo(orderId);
        assertThat(message.getOccurredAt()).isEqualTo(event.getOccurredAt());
        assertThat(message.getAmount()).isEqualByComparingTo("70.00");
        assertThat(message.getCurrency()).isEqualTo("BRL");
        assertThat(message.getItems()).extracting(StockItemRequestDTO::getQuantity).containsExactly(2, 1, 3);

        firstPrice.setPrice(new BigDecimal("999.00"));
        var saved = orderService.findById(orderId);
        assertThat(saved.getTotalValue()).isEqualByComparingTo("70.00");
        assertThat(saved.getItems()).filteredOn(item -> item.getProductId().equals(firstProductId))
                .allSatisfy(item -> assertThat(item.getUnitValue()).isEqualByComparingTo("10.00"));
    }

    @Test
    void outboxFailureRollsBackOrderAndItems() {
        doThrow(new DataIntegrityViolationException("Falha simulada na outbox"))
                .when(events).save(any(OutboxEvent.class));

        assertThatThrownBy(() -> orderService.createOrder(request(1)))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(orders.findAll()).isEmpty();
        assertThat(events.findAll()).isEmpty();
    }

    @Test
    void unavailableCatalogReturns503WithoutCreatingOrder() throws Exception {
        when(stockClient.findPrices(any())).thenThrow(new ResourceAccessException("Timeout"));

        mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(request(1))))
                .andExpect(status().isServiceUnavailable());

        assertThat(orders.findAll()).isEmpty();
        assertThat(events.findAll()).isEmpty();
    }

    @Test
    void nonexistentProductReturns400WithoutCreatingOrder() throws Exception {
        when(stockClient.findPrices(any())).thenThrow(HttpClientErrorException.create(
                HttpStatus.NOT_FOUND, "Produto inexistente", null, new byte[0], StandardCharsets.UTF_8));

        mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(request(1))))
                .andExpect(status().isBadRequest());

        assertThat(orders.findAll()).isEmpty();
        assertThat(events.findAll()).isEmpty();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {0, -1})
    void invalidQuantityDoesNotQueryCatalog(Integer quantity) throws Exception {
        mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(request(quantity))))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(stockClient);
        assertThat(orders.findAll()).isEmpty();
        assertThat(events.findAll()).isEmpty();
    }

    @Test
    void incompleteCatalogResponseDoesNotPersistPartialOrder() {
        when(stockClient.findPrices(any())).thenReturn(List.of());

        assertThatThrownBy(() -> orderService.createOrder(request(1)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(orders.findAll()).isEmpty();
        assertThat(events.findAll()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 50})
    void insufficientStockReturnsConflictWithoutCreatingOrderOrOutbox(int available) throws Exception {
        firstPrice.setAvailableStock(available);
        String message = available == 0
                ? "Produto fora de estoque: " + firstProductId
                : "Estoque insuficiente para o produto " + firstProductId + ". Disponível: " + available;

        mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(request(available + 1))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(message));

        assertThat(orders.findAll()).isEmpty();
        assertThat(events.findAll()).isEmpty();
    }

    @Test
    void repeatedProductsAreCheckedByTheirTotalQuantity() throws Exception {
        firstPrice.setAvailableStock(4);
        OrderRequestDTO request = new OrderRequestDTO(UUID.randomUUID(), List.of(
                new OrderItemRequestDTO(firstProductId, 2), new OrderItemRequestDTO(firstProductId, 3)));

        mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(request)))
                .andExpect(status().isConflict());

        verify(stockClient).findPrices(List.of(firstProductId));
        assertThat(orders.findAll()).isEmpty();
        assertThat(events.findAll()).isEmpty();
    }

    @Test
    void exactAvailableQuantityAllowsOrderCreation() {
        firstPrice.setAvailableStock(5);

        var order = orderService.createOrder(request(5));

        assertThat(order.getStatus()).isEqualTo(StatusOrder.CREATED);
        assertThat(order.getTotalValue()).isEqualByComparingTo("50.00");
        assertThat(orders.count()).isEqualTo(1);
        assertThat(events.count()).isEqualTo(1);
    }

    @Test
    void oneUnavailableProductRejectsWholeOrder() throws Exception {
        when(stockClient.findPrices(any())).thenReturn(List.of(firstPrice,
                new ProductPriceDTO(secondProductId, new BigDecimal("20.00"), 0)));
        OrderRequestDTO request = new OrderRequestDTO(UUID.randomUUID(), List.of(
                new OrderItemRequestDTO(firstProductId, 1), new OrderItemRequestDTO(secondProductId, 1)));

        mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(request)))
                .andExpect(status().isConflict());

        assertThat(orders.findAll()).isEmpty();
        assertThat(events.findAll()).isEmpty();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {-1})
    void missingOrInvalidAvailabilityReturns503WithoutCreatingOrder(Integer available) throws Exception {
        firstPrice.setAvailableStock(available);

        mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(request(1))))
                .andExpect(status().isServiceUnavailable());

        assertThat(orders.findAll()).isEmpty();
        assertThat(events.findAll()).isEmpty();
    }

    @Test
    void combinedQuantityOverflowIsRejectedBeforeConsultingCatalog() {
        OrderRequestDTO request = new OrderRequestDTO(UUID.randomUUID(), List.of(
                new OrderItemRequestDTO(firstProductId, Integer.MAX_VALUE), new OrderItemRequestDTO(firstProductId, 1)));

        assertThatThrownBy(() -> orderService.createOrder(request)).isInstanceOf(InvalidOrderRequestException.class);

        verifyNoInteractions(stockClient);
        assertThat(orders.findAll()).isEmpty();
        assertThat(events.findAll()).isEmpty();
    }

    @Test
    void concurrentReservedEventsMoveCreatedOrderToWaitingOnlyOnce() throws Exception {
        UUID orderId = orderService.createOrder(request(1)).getOrderId();
        StockReservedEvent event = reservedEvent(orderId);

        concurrently(() -> { stockReservedListener.hearStockReserved(event); return true; },
                () -> { stockReservedListener.hearStockReserved(event); return true; });

        assertThat(orderService.findById(orderId).getStatus()).isEqualTo(StatusOrder.WAITING_PAYMENT);
        Instant updatedAt = orders.findById(orderId).orElseThrow().getUpdatedAt();
        stockReservedListener.hearStockReserved(event);
        assertThat(orders.findById(orderId).orElseThrow().getUpdatedAt()).isEqualTo(updatedAt);
        assertThat(events.findAll()).hasSize(1);
    }

    @ParameterizedTest
    @EnumSource(value = StatusOrder.class, names = "CREATED", mode = EnumSource.Mode.EXCLUDE)
    void lateReservedEventDoesNotChangeExistingStatus(StatusOrder status) {
        UUID orderId = orderService.createOrder(request(1)).getOrderId();
        var order = orders.findById(orderId).orElseThrow();
        order.setStatus(status);
        orders.saveAndFlush(order);
        Instant updatedAt = orders.findById(orderId).orElseThrow().getUpdatedAt();

        stockReservedListener.hearStockReserved(reservedEvent(orderId));

        assertThat(orderService.findById(orderId).getStatus()).isEqualTo(status);
        assertThat(orders.findById(orderId).orElseThrow().getUpdatedAt()).isEqualTo(updatedAt);
    }

    @Test
    void concurrentApprovalAndReservedEventKeepPaidStatus() throws Exception {
        UUID orderId = orderService.createOrder(request(1)).getOrderId();
        StockReservedEvent event = reservedEvent(orderId);
        stockReservedListener.hearStockReserved(event);

        concurrently(() -> { paymentApprovedListener.hearPaymentApproved(approvedEvent(orderId)); return true; },
                () -> { stockReservedListener.hearStockReserved(event); return true; });

        assertThat(orderService.findById(orderId).getStatus()).isEqualTo(StatusOrder.PAID);
        assertThat(events.findAll()).singleElement()
                .satisfies(result -> assertThat(result.getExchange()).isEqualTo("order.created"));
    }

    @Test
    void mismatchedReservationAmountDoesNotChangeOrderStatus() {
        UUID orderId = orderService.createOrder(request(1)).getOrderId();
        StockReservedEvent event = reservedEvent(orderId);
        event.setAmount(new BigDecimal("99.00"));

        assertThatThrownBy(() -> stockReservedListener.hearStockReserved(event))
                .isInstanceOf(InvalidOrderRequestException.class);

        assertThat(orderService.findById(orderId).getStatus()).isEqualTo(StatusOrder.CREATED);
        assertThat(events.findAll()).hasSize(1);
    }

    @Test
    void unknownOrderIsRejectedInsteadOfBeingCreatedByReservationEvent() {
        UUID orderId = UUID.randomUUID();

        assertThatThrownBy(() -> stockReservedListener.hearStockReserved(reservedEvent(orderId)))
                .isInstanceOf(OrderNotFoundException.class);

        assertThat(orders.findAll()).isEmpty();
        assertThat(events.findAll()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"event", "eventId", "orderId", "occurredAt", "reservationId", "amount", "negativeAmount", "currency", "foreignCurrency"})
    void malformedReservedEventDoesNotChangeOrderStatus(String invalidField) {
        UUID orderId = orderService.createOrder(request(1)).getOrderId();
        StockReservedEvent event = reservedEvent(orderId);
        switch (invalidField) {
            case "event" -> event = null;
            case "eventId" -> event.setEventId(null);
            case "orderId" -> event.setOrderId(null);
            case "occurredAt" -> event.setOccurredAt(null);
            case "reservationId" -> event.setReservationId(null);
            case "amount" -> event.setAmount(null);
            case "negativeAmount" -> event.setAmount(new BigDecimal("-1.00"));
            case "currency" -> event.setCurrency(null);
            case "foreignCurrency" -> event.setCurrency("USD");
            default -> throw new IllegalArgumentException(invalidField);
        }
        StockReservedEvent invalidEvent = event;

        assertThatThrownBy(() -> stockReservedListener.hearStockReserved(invalidEvent))
                .isInstanceOf(InvalidOrderRequestException.class);

        assertThat(orderService.findById(orderId).getStatus()).isEqualTo(StatusOrder.CREATED);
        assertThat(events.findAll()).hasSize(1);
    }

    @ParameterizedTest
    @EnumSource(value = StatusOrder.class, names = {"CREATED", "WAITING_PAYMENT"})
    void approvalMovesOrderToPaidWithoutGeneratingStockConfirmation(StatusOrder initialStatus) {
        UUID orderId = orderService.createOrder(request(1)).getOrderId();
        if (initialStatus == StatusOrder.WAITING_PAYMENT) {
            stockReservedListener.hearStockReserved(reservedEvent(orderId));
        }
        PaymentApprovedEvent event = approvedEvent(orderId);

        paymentApprovedListener.hearPaymentApproved(event);
        Instant updatedAt = orders.findById(orderId).orElseThrow().getUpdatedAt();
        paymentApprovedListener.hearPaymentApproved(event);

        assertThat(orderService.findById(orderId).getStatus()).isEqualTo(StatusOrder.PAID);
        assertThat(orders.findById(orderId).orElseThrow().getUpdatedAt()).isEqualTo(updatedAt);
        assertThat(events.findAll()).hasSize(1);
        assertThat(events.findAll().getFirst().getExchange()).isEqualTo("order.created");
    }

    @Test
    void approvalBeforeReservationPreservesPaidAndUpdatedAtWhenReservationArrives() {
        UUID orderId = orderService.createOrder(request(1)).getOrderId();
        paymentApprovedListener.hearPaymentApproved(approvedEvent(orderId));
        Instant updatedAt = orders.findById(orderId).orElseThrow().getUpdatedAt();

        stockReservedListener.hearStockReserved(reservedEvent(orderId));

        assertThat(orderService.findById(orderId).getStatus()).isEqualTo(StatusOrder.PAID);
        assertThat(orders.findById(orderId).orElseThrow().getUpdatedAt()).isEqualTo(updatedAt);
        assertThat(events.findAll()).hasSize(1);
    }

    @Test
    void concurrentApprovalEventsChangeCreatedOrderOnlyOnce() throws Exception {
        UUID orderId = orderService.createOrder(request(1)).getOrderId();
        PaymentApprovedEvent event = approvedEvent(orderId);
        concurrently(() -> { paymentApprovedListener.hearPaymentApproved(event); return true; },
                () -> { paymentApprovedListener.hearPaymentApproved(event); return true; });
        Instant updatedAt = orders.findById(orderId).orElseThrow().getUpdatedAt();

        paymentApprovedListener.hearPaymentApproved(event);

        assertThat(orderService.findById(orderId).getStatus()).isEqualTo(StatusOrder.PAID);
        assertThat(orders.findById(orderId).orElseThrow().getUpdatedAt()).isEqualTo(updatedAt);
        assertThat(events.findAll()).hasSize(1);
    }

    @Test
    void concurrentApprovalAndReservationOnCreatedOrderKeepPaid() throws Exception {
        UUID orderId = orderService.createOrder(request(1)).getOrderId();
        concurrently(() -> { paymentApprovedListener.hearPaymentApproved(approvedEvent(orderId)); return true; },
                () -> { stockReservedListener.hearStockReserved(reservedEvent(orderId)); return true; });

        assertThat(orderService.findById(orderId).getStatus()).isEqualTo(StatusOrder.PAID);
        assertThat(events.findAll()).hasSize(1);
    }

    @ParameterizedTest
    @EnumSource(value = StatusOrder.class, names = {"CREATED", "WAITING_PAYMENT"}, mode = EnumSource.Mode.EXCLUDE)
    void lateApprovalDoesNotChangeOtherStatuses(StatusOrder status) {
        UUID orderId = orderService.createOrder(request(1)).getOrderId();
        var order = orders.findById(orderId).orElseThrow();
        order.setStatus(status);
        orders.saveAndFlush(order);
        Instant updatedAt = orders.findById(orderId).orElseThrow().getUpdatedAt();

        paymentApprovedListener.hearPaymentApproved(approvedEvent(orderId));

        assertThat(orderService.findById(orderId).getStatus()).isEqualTo(status);
        assertThat(orders.findById(orderId).orElseThrow().getUpdatedAt()).isEqualTo(updatedAt);
        assertThat(events.findAll()).hasSize(1);
    }

    @Test
    void mismatchedPaymentAmountDoesNotMarkOrderAsPaid() {
        UUID orderId = orderService.createOrder(request(1)).getOrderId();
        PaymentApprovedEvent event = approvedEvent(orderId);
        event.setAmount(new BigDecimal("99.00"));

        assertThatThrownBy(() -> paymentApprovedListener.hearPaymentApproved(event))
                .isInstanceOf(InvalidOrderRequestException.class);

        assertThat(orderService.findById(orderId).getStatus()).isEqualTo(StatusOrder.CREATED);
        assertThat(events.findAll()).hasSize(1);
    }

    @Test
    void unknownOrderIsRejectedInsteadOfBeingCreatedByApproval() {
        assertThatThrownBy(() -> paymentApprovedListener.hearPaymentApproved(approvedEvent(UUID.randomUUID())))
                .isInstanceOf(OrderNotFoundException.class);

        assertThat(orders.findAll()).isEmpty();
        assertThat(events.findAll()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"event", "eventId", "orderId", "occurredAt", "paymentId", "reservationId",
            "amount", "negativeAmount", "currency", "foreignCurrency"})
    void malformedApprovalDoesNotChangeOrderStatus(String invalidField) {
        UUID orderId = orderService.createOrder(request(1)).getOrderId();
        PaymentApprovedEvent event = approvedEvent(orderId);
        switch (invalidField) {
            case "event" -> event = null;
            case "eventId" -> event.setEventId(null);
            case "orderId" -> event.setOrderId(null);
            case "occurredAt" -> event.setOccurredAt(null);
            case "paymentId" -> event.setPaymentId(null);
            case "reservationId" -> event.setReservationId(null);
            case "amount" -> event.setAmount(null);
            case "negativeAmount" -> event.setAmount(new BigDecimal("-1.00"));
            case "currency" -> event.setCurrency(null);
            case "foreignCurrency" -> event.setCurrency("USD");
            default -> throw new IllegalArgumentException(invalidField);
        }
        PaymentApprovedEvent invalidEvent = event;

        assertThatThrownBy(() -> paymentApprovedListener.hearPaymentApproved(invalidEvent))
                .isInstanceOf(InvalidOrderRequestException.class);

        assertThat(orderService.findById(orderId).getStatus()).isEqualTo(StatusOrder.CREATED);
        assertThat(events.findAll()).hasSize(1);
    }

    @Test
    void concurrentStockFailuresCancelCreatedOrderOnlyOnceAndLateEventsKeepCanceled() throws Exception {
        UUID orderId = orderService.createOrder(request(1)).getOrderId();
        StockReservationFailedEvent event = failedEvent(orderId);
        concurrently(() -> { stockReservationFailedListener.hearStockReservationFailed(event); return true; },
                () -> { stockReservationFailedListener.hearStockReservationFailed(event); return true; });
        Instant updatedAt = orders.findById(orderId).orElseThrow().getUpdatedAt();

        stockReservationFailedListener.hearStockReservationFailed(event);
        stockReservedListener.hearStockReserved(reservedEvent(orderId));
        paymentApprovedListener.hearPaymentApproved(approvedEvent(orderId));

        assertThat(orderService.findById(orderId).getStatus()).isEqualTo(StatusOrder.CANCELED);
        assertThat(orders.findById(orderId).orElseThrow().getUpdatedAt()).isEqualTo(updatedAt);
        assertThat(events.findAll()).singleElement()
                .satisfies(result -> assertThat(result.getExchange()).isEqualTo("order.created"));
    }

    @ParameterizedTest
    @EnumSource(value = StatusOrder.class, names = "CREATED", mode = EnumSource.Mode.EXCLUDE)
    void lateStockFailurePreservesExistingStatusAndUpdatedAt(StatusOrder status) {
        UUID orderId = orderService.createOrder(request(1)).getOrderId();
        var order = orders.findById(orderId).orElseThrow();
        order.setStatus(status);
        orders.saveAndFlush(order);
        Instant updatedAt = orders.findById(orderId).orElseThrow().getUpdatedAt();

        stockReservationFailedListener.hearStockReservationFailed(failedEvent(orderId));

        assertThat(orderService.findById(orderId).getStatus()).isEqualTo(status);
        assertThat(orders.findById(orderId).orElseThrow().getUpdatedAt()).isEqualTo(updatedAt);
        assertThat(events.findAll()).hasSize(1);
    }

    @Test
    void unknownOrderIsRejectedInsteadOfBeingCreatedByStockFailure() {
        assertThatThrownBy(() -> stockReservationFailedListener.hearStockReservationFailed(failedEvent(UUID.randomUUID())))
                .isInstanceOf(OrderNotFoundException.class);
        assertThat(orders.findAll()).isEmpty();
        assertThat(events.findAll()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"event", "eventId", "orderId", "occurredAt", "failureReason", "blankReason"})
    void malformedStockFailureDoesNotCancelOrder(String invalidField) {
        UUID orderId = orderService.createOrder(request(1)).getOrderId();
        StockReservationFailedEvent event = failedEvent(orderId);
        switch (invalidField) {
            case "event" -> event = null;
            case "eventId" -> event.setEventId(null);
            case "orderId" -> event.setOrderId(null);
            case "occurredAt" -> event.setOccurredAt(null);
            case "failureReason" -> event.setFailureReason(null);
            case "blankReason" -> event.setFailureReason(" ");
            default -> throw new IllegalArgumentException(invalidField);
        }
        StockReservationFailedEvent invalidEvent = event;

        assertThatThrownBy(() -> stockReservationFailedListener.hearStockReservationFailed(invalidEvent))
                .isInstanceOf(InvalidOrderRequestException.class);
        assertThat(orderService.findById(orderId).getStatus()).isEqualTo(StatusOrder.CREATED);
        assertThat(events.findAll()).hasSize(1);
    }

    private StockReservationFailedEvent failedEvent(UUID orderId) {
        return new StockReservationFailedEvent(UUID.randomUUID(), orderId, Instant.now(), "Estoque insuficiente");
    }

    private PaymentApprovedEvent approvedEvent(UUID orderId) {
        return new PaymentApprovedEvent(UUID.randomUUID(), orderId, Instant.now(), UUID.randomUUID(),
                UUID.randomUUID(), new BigDecimal("10.0"), "BRL");
    }

    private StockReservedEvent reservedEvent(UUID orderId) {
        return new StockReservedEvent(UUID.randomUUID(), orderId, Instant.now(), UUID.randomUUID(),
                new BigDecimal("10.0"), "BRL");
    }

    private <T> List<T> concurrently(Callable<T> first, Callable<T> second) throws Exception {
        try (var executor = Executors.newFixedThreadPool(2)) {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            var tasks = List.of(first, second).stream().map(task -> executor.submit(() -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timeout aguardando início concorrente");
                }
                return task.call();
            })).toList();
            try {
                assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
                start.countDown();
                return List.of(tasks.get(0).get(10, TimeUnit.SECONDS), tasks.get(1).get(10, TimeUnit.SECONDS));
            } finally {
                executor.shutdownNow();
            }
        }
    }

    private OrderRequestDTO request(Integer quantity) {
        return new OrderRequestDTO(UUID.randomUUID(), List.of(new OrderItemRequestDTO(firstProductId, quantity)));
    }

    @TestConfiguration
    static class JsonConfig {
        @Bean
        JsonMapper jsonMapper() {
            return JsonMapper.builder().build();
        }
    }
}
