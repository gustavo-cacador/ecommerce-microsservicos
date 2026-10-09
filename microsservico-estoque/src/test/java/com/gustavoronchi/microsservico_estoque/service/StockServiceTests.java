package com.gustavoronchi.microsservico_estoque.service;

import com.gustavoronchi.microsservico_estoque.domain.entities.Product;
import com.gustavoronchi.microsservico_estoque.domain.entities.OutboxEvent;
import com.gustavoronchi.microsservico_estoque.domain.repository.OutboxEventRepository;
import com.gustavoronchi.microsservico_estoque.domain.repository.ProductRepository;
import com.gustavoronchi.microsservico_estoque.domain.repository.StockReservationRepository;
import com.gustavoronchi.microsservico_estoque.dto.StockItemRequestDTO;
import com.gustavoronchi.microsservico_estoque.enums.ReservationStatus;
import com.gustavoronchi.microsservico_estoque.exception.InvalidStockRequestException;
import com.gustavoronchi.microsservico_estoque.exception.StockInconsistencyException;
import com.gustavoronchi.microsservico_estoque.exception.StockReservationNotFoundException;
import com.gustavoronchi.microsservico_estoque.messaging.StockActionListener;
import com.gustavoronchi.microsservico_estoque.messaging.StockActionMessage;
import com.gustavoronchi.microsservico_estoque.messaging.OrderCreatedEvent;
import com.gustavoronchi.microsservico_estoque.messaging.OrderCreatedListener;
import com.gustavoronchi.microsservico_estoque.messaging.StockReservedEvent;
import com.gustavoronchi.microsservico_estoque.messaging.StockReservationFailedEvent;
import com.gustavoronchi.microsservico_estoque.messaging.PaymentApprovedEvent;
import com.gustavoronchi.microsservico_estoque.messaging.PaymentApprovedListener;
import com.gustavoronchi.microsservico_estoque.messaging.PaymentRefusedEvent;
import com.gustavoronchi.microsservico_estoque.messaging.PaymentRefusedListener;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
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
import java.util.ArrayList;
import java.util.Comparator;
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
import static org.mockito.Mockito.reset;

@DataJpaTest(showSql = false, properties = "spring.sql.init.mode=never")
@Import({StockService.class, StockActionListener.class, OrderCreatedListener.class,
        PaymentApprovedListener.class, PaymentRefusedListener.class, StockServiceTests.JsonConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class StockServiceTests {

    @Autowired
    private StockService service;
    @Autowired
    private ProductRepository products;
    @Autowired
    private StockReservationRepository reservations;
    @Autowired
    private StockActionListener listener;
    @Autowired
    private OrderCreatedListener orderCreatedListener;
    @Autowired
    private PaymentApprovedListener paymentApprovedListener;
    @Autowired
    private PaymentRefusedListener paymentRefusedListener;
    @MockitoSpyBean
    private OutboxEventRepository outbox;
    @Autowired
    private JsonMapper jsonMapper;

    @Test
    void duplicateOrderCreatedEventsReserveOnlyOnce() throws Exception {
        Product product = product(5);
        OrderCreatedEvent event = new OrderCreatedEvent(UUID.randomUUID(), UUID.randomUUID(), Instant.now(),
                List.of(item(product, 2)), new BigDecimal("12.34"), "BRL");

        concurrently(() -> { orderCreatedListener.hearOrderCreated(event); return true; },
                () -> { orderCreatedListener.hearOrderCreated(event); return true; });

        assertStock(product, 5, 2);
        assertThat(reservations.findByOrderId(event.getOrderId()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.RESERVED);
        OutboxEvent saved = outbox.findBySourceEventId(event.getEventId()).orElseThrow();
        StockReservedEvent result = jsonMapper.readValue(saved.getPayload(), StockReservedEvent.class);
        assertThat(saved.getExchange()).isEqualTo("stock.reserved");
        assertThat(saved.getPublishedAt()).isNull();
        assertThat(result.getEventId()).isEqualTo(saved.getEventId()).isNotEqualTo(event.getEventId());
        assertThat(result.getOrderId()).isEqualTo(event.getOrderId());
        assertThat(result.getOccurredAt()).isEqualTo(saved.getOccurredAt());
        assertThat(result.getReservationId())
                .isEqualTo(reservations.findByOrderId(event.getOrderId()).orElseThrow().getId());
        assertThat(result.getAmount()).isEqualByComparingTo("12.34");
        assertThat(result.getCurrency()).isEqualTo("BRL");

        orderCreatedListener.hearOrderCreated(event);
        assertThat(outbox.findBySourceEventId(event.getEventId()).orElseThrow().getEventId()).isEqualTo(saved.getEventId());
        assertStock(product, 5, 2);
    }

    @Test
    void unsuccessfulOrderCreatedReservationPersistsFailureWithoutPartialStockChange() {
        Product first = product(5);
        Product second = product(0);
        OrderCreatedEvent event = new OrderCreatedEvent(UUID.randomUUID(), UUID.randomUUID(), Instant.now(),
                List.of(item(first, 2), item(second, 1)), new BigDecimal("30.00"), "BRL");

        orderCreatedListener.hearOrderCreated(event);

        assertStock(first, 5, 0);
        assertStock(second, 0, 0);
        assertThat(reservations.findByOrderId(event.getOrderId())).isEmpty();
        OutboxEvent saved = outbox.findBySourceEventId(event.getEventId()).orElseThrow();
        StockReservationFailedEvent result = jsonMapper.readValue(saved.getPayload(), StockReservationFailedEvent.class);
        assertThat(saved.getExchange()).isEqualTo("stock.reservation.failed");
        assertThat(saved.getPublishedAt()).isNull();
        assertThat(result.getEventId()).isEqualTo(saved.getEventId());
        assertThat(result.getOrderId()).isEqualTo(event.getOrderId());
        assertThat(result.getOccurredAt()).isEqualTo(saved.getOccurredAt());
        assertThat(result.getFailureReason()).contains("Estoque insuficiente");
    }

    @Test
    void outboxFailureRollsBackReservationAndAllowsRedelivery() {
        Product product = product(5);
        OrderCreatedEvent event = new OrderCreatedEvent(UUID.randomUUID(), UUID.randomUUID(), Instant.now(),
                List.of(item(product, 2)), new BigDecimal("20.00"), "BRL");
        doThrow(new DataIntegrityViolationException("Falha simulada na outbox"))
                .when(outbox).save(any(OutboxEvent.class));

        assertThatThrownBy(() -> orderCreatedListener.hearOrderCreated(event))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertStock(product, 5, 0);
        assertThat(reservations.findByOrderId(event.getOrderId())).isEmpty();
        assertThat(outbox.findBySourceEventId(event.getEventId())).isEmpty();

        reset(outbox);
        orderCreatedListener.hearOrderCreated(event);
        assertStock(product, 5, 2);
        assertThat(outbox.findBySourceEventId(event.getEventId())).isPresent();
    }

    @Test
    void repeatedFailedEventKeepsOriginalResultAfterReplenishment() {
        Product product = product(0);
        OrderCreatedEvent event = new OrderCreatedEvent(UUID.randomUUID(), UUID.randomUUID(), Instant.now(),
                List.of(item(product, 2)), new BigDecimal("20.00"), "BRL");
        orderCreatedListener.hearOrderCreated(event);
        UUID resultId = outbox.findBySourceEventId(event.getEventId()).orElseThrow().getEventId();
        product.setQuantityAvailable(5);
        products.saveAndFlush(product);

        orderCreatedListener.hearOrderCreated(event);

        assertStock(product, 5, 0);
        assertThat(reservations.findByOrderId(event.getOrderId())).isEmpty();
        OutboxEvent saved = outbox.findBySourceEventId(event.getEventId()).orElseThrow();
        assertThat(saved.getEventId()).isEqualTo(resultId);
        assertThat(saved.getExchange()).isEqualTo("stock.reservation.failed");
    }

    @Test
    void concurrentMissingProductEventsPersistOnlyOneResult() throws Exception {
        OrderCreatedEvent event = new OrderCreatedEvent(UUID.randomUUID(), UUID.randomUUID(), Instant.now(),
                List.of(new StockItemRequestDTO(UUID.randomUUID(), 1)), BigDecimal.TEN, "BRL");

        concurrently(() -> { orderCreatedListener.hearOrderCreated(event); return true; },
                () -> { orderCreatedListener.hearOrderCreated(event); return true; });

        assertThat(outbox.findAll()).filteredOn(result -> result.getSourceEventId().equals(event.getEventId()))
                .singleElement().satisfies(result -> assertThat(result.getExchange()).isEqualTo("stock.reservation.failed"));
        assertThat(reservations.findByOrderId(event.getOrderId())).isEmpty();
    }

    @Test
    void concurrentOrdersForLastUnitPersistSuccessAndFailure() throws Exception {
        Product product = product(1);
        OrderCreatedEvent first = new OrderCreatedEvent(UUID.randomUUID(), UUID.randomUUID(), Instant.now(),
                List.of(item(product, 1)), BigDecimal.TEN, "BRL");
        OrderCreatedEvent second = new OrderCreatedEvent(UUID.randomUUID(), UUID.randomUUID(), Instant.now(),
                List.of(item(product, 1)), BigDecimal.TEN, "BRL");

        concurrently(() -> { orderCreatedListener.hearOrderCreated(first); return true; },
                () -> { orderCreatedListener.hearOrderCreated(second); return true; });

        assertStock(product, 1, 1);
        assertThat(List.of(outbox.findBySourceEventId(first.getEventId()).orElseThrow().getExchange(),
                outbox.findBySourceEventId(second.getEventId()).orElseThrow().getExchange()))
                .containsExactlyInAnyOrder("stock.reserved", "stock.reservation.failed");
    }

    @Test
    void invalidQuantityDoesNotPersistResultEvent() {
        Product product = product(5);
        OrderCreatedEvent event = new OrderCreatedEvent(UUID.randomUUID(), UUID.randomUUID(), Instant.now(),
                List.of(item(product, 0)), BigDecimal.TEN, "BRL");

        assertThatThrownBy(() -> orderCreatedListener.hearOrderCreated(event))
                .isInstanceOf(InvalidStockRequestException.class);

        assertStock(product, 5, 0);
        assertThat(reservations.findByOrderId(event.getOrderId())).isEmpty();
        assertThat(outbox.findBySourceEventId(event.getEventId())).isEmpty();
    }

    @Test
    void malformedOrderCreatedEventDoesNotReserveStock() {
        Product product = product(5);
        OrderCreatedEvent event = new OrderCreatedEvent(null, UUID.randomUUID(), Instant.now(),
                List.of(item(product, 2)), new BigDecimal("20.00"), "BRL");

        assertThatThrownBy(() -> orderCreatedListener.hearOrderCreated(event))
                .isInstanceOf(InvalidStockRequestException.class);

        assertStock(product, 5, 0);
        assertThat(reservations.findByOrderId(event.getOrderId())).isEmpty();
    }

    @Test
    void repeatedProductsCannotExceedAvailableStock() {
        Product product = product(5);
        UUID orderId = UUID.randomUUID();

        assertThat(service.reserve(orderId, List.of(item(product, 4), item(product, 4))).isSuccess()).isFalse();
        assertStock(product, 5, 0);
        assertThat(reservations.findByOrderId(orderId)).isEmpty();
    }

    @Test
    void repeatedReservationUsesConsolidatedQuantitiesAndOriginalPrice() {
        Product product = product(5);
        UUID orderId = UUID.randomUUID();
        service.reserve(orderId, List.of(item(product, 2), item(product, 3)));
        Product updated = products.findById(product.getId()).orElseThrow();
        updated.setPrice(new BigDecimal("99.00"));
        products.saveAndFlush(updated);

        var response = service.reserve(orderId, List.of(item(product, 5)));

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getItems()).hasSize(1);
        assertThat(response.getItems().getFirst().getPrice()).isEqualByComparingTo("10.00");
        assertStock(product, 5, 5);
        assertThatThrownBy(() -> service.reserve(orderId, List.of(item(product, 4))))
                .isInstanceOf(StockInconsistencyException.class);
    }

    @Test
    void shortageDoesNotLeavePartialReservation() {
        Product first = product(5);
        Product second = product(0);
        UUID orderId = UUID.randomUUID();

        assertThat(service.reserve(orderId, List.of(item(first, 2), item(second, 1))).isSuccess()).isFalse();
        assertStock(first, 5, 0);
        assertStock(second, 0, 0);
        assertThat(reservations.findByOrderId(orderId)).isEmpty();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {0, -1})
    void invalidQuantityDoesNotChangeStock(Integer quantity) {
        Product product = product(5);

        assertThatThrownBy(() -> service.reserve(UUID.randomUUID(), List.of(item(product, quantity))))
                .isInstanceOf(InvalidStockRequestException.class);
        assertStock(product, 5, 0);
    }

    @Test
    void invalidItemsAndQuantityOverflowAreRejected() {
        Product product = product(5);
        UUID orderId = UUID.randomUUID();

        assertThatThrownBy(() -> service.reserve(null, List.of(item(product, 1))))
                .isInstanceOf(InvalidStockRequestException.class);
        assertThatThrownBy(() -> service.reserve(orderId, null))
                .isInstanceOf(InvalidStockRequestException.class);
        assertThatThrownBy(() -> service.reserve(orderId, List.of()))
                .isInstanceOf(InvalidStockRequestException.class);
        assertThatThrownBy(() -> service.reserve(orderId, List.of(new StockItemRequestDTO(null, 1))))
                .isInstanceOf(InvalidStockRequestException.class);
        assertThatThrownBy(() -> service.reserve(orderId,
                List.of(item(product, Integer.MAX_VALUE), item(product, 1))))
                .isInstanceOf(InvalidStockRequestException.class);
        assertStock(product, 5, 0);
    }

    @Test
    void onlyOneOrderCanReserveTheLastUnit() throws Exception {
        Product product = product(1);

        var results = concurrently(
                () -> service.reserve(UUID.randomUUID(), List.of(item(product, 1))).isSuccess(),
                () -> service.reserve(UUID.randomUUID(), List.of(item(product, 1))).isSuccess());

        assertThat(results).containsExactlyInAnyOrder(true, false);
        assertStock(product, 1, 1);
    }

    @Test
    void simultaneousDuplicateReservationOnlyReservesOnce() throws Exception {
        Product product = product(1);
        UUID orderId = UUID.randomUUID();

        var results = concurrently(
                () -> service.reserve(orderId, List.of(item(product, 1))).isSuccess(),
                () -> service.reserve(orderId, List.of(item(product, 1))).isSuccess());

        assertThat(results).containsExactly(true, true);
        assertStock(product, 1, 1);
    }

    @Test
    void oppositeProductOrderDoesNotDeadlock() throws Exception {
        Product first = product(2);
        Product second = product(2);

        var results = concurrently(
                () -> service.reserve(UUID.randomUUID(), List.of(item(first, 1), item(second, 1))).isSuccess(),
                () -> service.reserve(UUID.randomUUID(), List.of(item(second, 1), item(first, 1))).isSuccess());

        assertThat(results).containsExactly(true, true);
        assertStock(first, 2, 2);
        assertStock(second, 2, 2);
    }

    @Test
    void duplicateConfirmationUsesPersistedItemsAndOnlyDeductsOnce() throws Exception {
        Product product = product(5);
        UUID orderId = UUID.randomUUID();
        service.reserve(orderId, List.of(item(product, 2)));
        StockActionMessage message = new StockActionMessage(orderId, List.of(item(product, 100)));

        concurrently(() -> { listener.hearConfirmation(message); return true; },
                () -> { listener.hearConfirmation(message); return true; });

        assertStock(product, 3, 0);
        assertThat(reservations.findByOrderId(orderId).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.CONFIRMED);
        assertThatThrownBy(() -> service.release(orderId)).isInstanceOf(StockInconsistencyException.class);
        assertThat(service.reserve(orderId, List.of(item(product, 2))).isSuccess()).isTrue();
        assertStock(product, 3, 0);
    }

    @Test
    void duplicateReleasePreservesAnotherOrdersReservation() throws Exception {
        Product product = product(5);
        UUID orderId = UUID.randomUUID();
        service.reserve(orderId, List.of(item(product, 2)));
        service.reserve(UUID.randomUUID(), List.of(item(product, 3)));
        StockActionMessage message = new StockActionMessage(orderId, List.of(item(product, 100)));

        concurrently(() -> { listener.hearRelease(message); return true; },
                () -> { listener.hearRelease(message); return true; });

        assertStock(product, 5, 3);
        assertThat(reservations.findByOrderId(orderId).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.RELEASED);
        assertThatThrownBy(() -> service.confirm(orderId)).isInstanceOf(StockInconsistencyException.class);
        assertThatThrownBy(() -> service.reserve(orderId, List.of(item(product, 2))))
                .isInstanceOf(StockInconsistencyException.class);
    }

    @Test
    void failureDuringConfirmationRollsBackAllItems() {
        List<Product> ordered = new ArrayList<>(List.of(product(5), product(5)));
        ordered.sort(Comparator.comparing(Product::getId));
        UUID orderId = UUID.randomUUID();
        service.reserve(orderId, List.of(item(ordered.get(0), 2), item(ordered.get(1), 2)));
        Product inconsistent = products.findById(ordered.get(1).getId()).orElseThrow();
        inconsistent.setQuantityReserved(0);
        products.saveAndFlush(inconsistent);

        assertThatThrownBy(() -> service.confirm(orderId)).isInstanceOf(StockInconsistencyException.class);

        assertStock(ordered.getFirst(), 5, 2);
        assertThat(reservations.findByOrderId(orderId).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.RESERVED);
    }

    @Test
    void unknownOrderCannotReleaseOrConfirmAggregatedStock() {
        assertThatThrownBy(() -> service.release(UUID.randomUUID()))
                .isInstanceOf(StockReservationNotFoundException.class);
        assertThatThrownBy(() -> service.confirm(UUID.randomUUID()))
                .isInstanceOf(StockReservationNotFoundException.class);
    }

    @TestConfiguration
    static class JsonConfig {
        @Bean
        JsonMapper jsonMapper() {
            return JsonMapper.builder().build();
        }
    }

    @Test
    void repeatedPaymentApprovalConfirmsPersistedItemsOnlyOnce() throws Exception {
        Product first = product(5);
        Product second = product(7);
        UUID orderId = UUID.randomUUID();
        service.reserve(orderId, List.of(item(first, 2), item(second, 3)));
        service.reserve(UUID.randomUUID(), List.of(item(first, 1)));
        PaymentApprovedEvent event = approval(orderId);

        concurrently(() -> { paymentApprovedListener.hearPaymentApproved(event); return true; },
                () -> { paymentApprovedListener.hearPaymentApproved(event); return true; });
        paymentApprovedListener.hearPaymentApproved(event);
        event.setEventId(UUID.randomUUID());
        paymentApprovedListener.hearPaymentApproved(event);

        assertStock(first, 3, 1);
        assertStock(second, 4, 0);
        assertThat(reservations.findByOrderId(orderId).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.CONFIRMED);
    }

    @Test
    void approvalWithAnotherOrdersReservationIsRejectedEvenAfterConfirmation() {
        Product product = product(5);
        UUID firstOrder = UUID.randomUUID();
        UUID secondOrder = UUID.randomUUID();
        service.reserve(firstOrder, List.of(item(product, 2)));
        service.reserve(secondOrder, List.of(item(product, 1)));
        PaymentApprovedEvent event = approval(firstOrder);
        event.setReservationId(reservations.findByOrderId(secondOrder).orElseThrow().getId());

        assertThatThrownBy(() -> paymentApprovedListener.hearPaymentApproved(event))
                .isInstanceOf(StockInconsistencyException.class);
        assertStock(product, 5, 3);
        assertThat(reservations.findByOrderId(firstOrder).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.RESERVED);

        paymentApprovedListener.hearPaymentApproved(approval(firstOrder));
        assertThatThrownBy(() -> paymentApprovedListener.hearPaymentApproved(event))
                .isInstanceOf(StockInconsistencyException.class);
        assertStock(product, 3, 1);
        assertThat(reservations.findByOrderId(secondOrder).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.RESERVED);
    }

    @Test
    void approvalCannotConfirmReleasedReservationOrUnknownOrder() {
        Product product = product(5);
        UUID orderId = UUID.randomUUID();
        service.reserve(orderId, List.of(item(product, 2)));
        PaymentApprovedEvent event = approval(orderId);
        service.release(orderId);

        assertThatThrownBy(() -> paymentApprovedListener.hearPaymentApproved(event))
                .isInstanceOf(StockInconsistencyException.class);
        event.setOrderId(UUID.randomUUID());
        assertThatThrownBy(() -> paymentApprovedListener.hearPaymentApproved(event))
                .isInstanceOf(StockReservationNotFoundException.class);
        assertStock(product, 5, 0);
        assertThat(reservations.findByOrderId(orderId).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.RELEASED);
    }

    @Test
    void failureDuringPaymentConfirmationRollsBackAllItems() {
        List<Product> ordered = new ArrayList<>(List.of(product(5), product(5)));
        ordered.sort(Comparator.comparing(Product::getId));
        UUID orderId = UUID.randomUUID();
        service.reserve(orderId, List.of(item(ordered.get(0), 2), item(ordered.get(1), 2)));
        Product inconsistent = products.findById(ordered.get(1).getId()).orElseThrow();
        inconsistent.setQuantityReserved(0);
        products.saveAndFlush(inconsistent);

        assertThatThrownBy(() -> paymentApprovedListener.hearPaymentApproved(approval(orderId)))
                .isInstanceOf(StockInconsistencyException.class);

        assertStock(ordered.getFirst(), 5, 2);
        assertStock(ordered.get(1), 5, 0);
        assertThat(reservations.findByOrderId(orderId).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.RESERVED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "eventId", "orderId", "occurredAt", "paymentId", "reservationId",
            "amount", "negativeAmount", "currency", "foreignCurrency"})
    void invalidApprovalDoesNotChangeReservation(String field) {
        Product product = product(5);
        UUID orderId = UUID.randomUUID();
        service.reserve(orderId, List.of(item(product, 2)));
        PaymentApprovedEvent event = approval(orderId);
        switch (field) {
            case "null" -> event = null;
            case "eventId" -> event.setEventId(null);
            case "orderId" -> event.setOrderId(null);
            case "occurredAt" -> event.setOccurredAt(null);
            case "paymentId" -> event.setPaymentId(null);
            case "reservationId" -> event.setReservationId(null);
            case "amount" -> event.setAmount(null);
            case "negativeAmount" -> event.setAmount(new BigDecimal("-1.00"));
            case "currency" -> event.setCurrency(null);
            case "foreignCurrency" -> event.setCurrency("USD");
        }
        PaymentApprovedEvent invalid = event;

        assertThatThrownBy(() -> paymentApprovedListener.hearPaymentApproved(invalid))
                .isInstanceOf(InvalidStockRequestException.class);
        assertStock(product, 5, 2);
        assertThat(reservations.findByOrderId(orderId).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.RESERVED);
    }

    @Test
    void confirmationWithNullReservationIdIsRejected() {
        assertThatThrownBy(() -> service.confirm(UUID.randomUUID(), null))
                .isInstanceOf(InvalidStockRequestException.class);
    }

    @Test
    void repeatedPaymentRefusalReleasesPersistedItemsAndPreservesAnotherOrdersReservation() throws Exception {
        Product first = product(5);
        Product second = product(7);
        UUID orderId = UUID.randomUUID();
        UUID otherOrderId = UUID.randomUUID();
        service.reserve(orderId, List.of(item(first, 2), item(second, 3)));
        service.reserve(otherOrderId, List.of(item(first, 1)));
        PaymentRefusedEvent event = refusal(orderId);

        concurrently(() -> { paymentRefusedListener.hearPaymentRefused(event); return true; },
                () -> { paymentRefusedListener.hearPaymentRefused(event); return true; });
        paymentRefusedListener.hearPaymentRefused(event);
        event.setEventId(UUID.randomUUID());
        paymentRefusedListener.hearPaymentRefused(event);

        assertStock(first, 5, 1);
        assertStock(second, 7, 0);
        assertThat(reservations.findByOrderId(orderId).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.RELEASED);
        assertThat(reservations.findByOrderId(otherOrderId).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.RESERVED);
        assertThatThrownBy(() -> paymentApprovedListener.hearPaymentApproved(approval(orderId)))
                .isInstanceOf(StockInconsistencyException.class);
        assertStock(first, 5, 1);
        assertStock(second, 7, 0);
    }

    @Test
    void refusalWithAnotherOrdersReservationIsRejectedEvenAfterRelease() {
        Product product = product(5);
        UUID firstOrder = UUID.randomUUID();
        UUID secondOrder = UUID.randomUUID();
        service.reserve(firstOrder, List.of(item(product, 2)));
        service.reserve(secondOrder, List.of(item(product, 1)));
        PaymentRefusedEvent event = refusal(firstOrder);
        event.setReservationId(reservations.findByOrderId(secondOrder).orElseThrow().getId());

        assertThatThrownBy(() -> paymentRefusedListener.hearPaymentRefused(event))
                .isInstanceOf(StockInconsistencyException.class);
        assertStock(product, 5, 3);
        assertThat(reservations.findByOrderId(firstOrder).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.RESERVED);

        paymentRefusedListener.hearPaymentRefused(refusal(firstOrder));
        assertThatThrownBy(() -> paymentRefusedListener.hearPaymentRefused(event))
                .isInstanceOf(StockInconsistencyException.class);
        assertStock(product, 5, 1);
        assertThat(reservations.findByOrderId(firstOrder).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.RELEASED);
        assertThat(reservations.findByOrderId(secondOrder).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.RESERVED);
    }

    @Test
    void refusalCannotReleaseConfirmedReservationOrUnknownOrder() {
        Product product = product(5);
        UUID orderId = UUID.randomUUID();
        service.reserve(orderId, List.of(item(product, 2)));
        PaymentRefusedEvent event = refusal(orderId);
        paymentApprovedListener.hearPaymentApproved(approval(orderId));

        assertThatThrownBy(() -> paymentRefusedListener.hearPaymentRefused(event))
                .isInstanceOf(StockInconsistencyException.class);
        event.setOrderId(UUID.randomUUID());
        assertThatThrownBy(() -> paymentRefusedListener.hearPaymentRefused(event))
                .isInstanceOf(StockReservationNotFoundException.class);
        assertStock(product, 3, 0);
        assertThat(reservations.findByOrderId(orderId).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.CONFIRMED);
    }

    @Test
    void failureDuringPaymentReleaseRollsBackAllItems() {
        List<Product> ordered = new ArrayList<>(List.of(product(5), product(5)));
        ordered.sort(Comparator.comparing(Product::getId));
        UUID orderId = UUID.randomUUID();
        service.reserve(orderId, List.of(item(ordered.get(0), 2), item(ordered.get(1), 2)));
        Product inconsistent = products.findById(ordered.get(1).getId()).orElseThrow();
        inconsistent.setQuantityReserved(0);
        products.saveAndFlush(inconsistent);

        assertThatThrownBy(() -> paymentRefusedListener.hearPaymentRefused(refusal(orderId)))
                .isInstanceOf(StockInconsistencyException.class);

        assertStock(ordered.getFirst(), 5, 2);
        assertStock(ordered.get(1), 5, 0);
        assertThat(reservations.findByOrderId(orderId).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.RESERVED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "eventId", "orderId", "occurredAt", "paymentId", "reservationId",
            "amount", "negativeAmount", "currency", "foreignCurrency"})
    void invalidRefusalDoesNotChangeReservation(String field) {
        Product product = product(5);
        UUID orderId = UUID.randomUUID();
        service.reserve(orderId, List.of(item(product, 2)));
        PaymentRefusedEvent event = refusal(orderId);
        switch (field) {
            case "null" -> event = null;
            case "eventId" -> event.setEventId(null);
            case "orderId" -> event.setOrderId(null);
            case "occurredAt" -> event.setOccurredAt(null);
            case "paymentId" -> event.setPaymentId(null);
            case "reservationId" -> event.setReservationId(null);
            case "amount" -> event.setAmount(null);
            case "negativeAmount" -> event.setAmount(new BigDecimal("-1.00"));
            case "currency" -> event.setCurrency(null);
            case "foreignCurrency" -> event.setCurrency("USD");
        }
        PaymentRefusedEvent invalid = event;

        assertThatThrownBy(() -> paymentRefusedListener.hearPaymentRefused(invalid))
                .isInstanceOf(InvalidStockRequestException.class);
        assertStock(product, 5, 2);
        assertThat(reservations.findByOrderId(orderId).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.RESERVED);
    }

    @Test
    void releaseWithNullReservationIdIsRejected() {
        assertThatThrownBy(() -> service.release(UUID.randomUUID(), null))
                .isInstanceOf(InvalidStockRequestException.class);
    }

    private PaymentRefusedEvent refusal(UUID orderId) {
        return new PaymentRefusedEvent(UUID.randomUUID(), orderId, Instant.now(), UUID.randomUUID(),
                reservations.findByOrderId(orderId).orElseThrow().getId(), new BigDecimal("12.34"), "BRL");
    }

    private PaymentApprovedEvent approval(UUID orderId) {
        return new PaymentApprovedEvent(UUID.randomUUID(), orderId, Instant.now(), UUID.randomUUID(),
                reservations.findByOrderId(orderId).orElseThrow().getId(), new BigDecimal("12.34"), "BRL");
    }

    private Product product(int quantity) {
        return products.saveAndFlush(new Product(null, "Produto de teste", null,
                new BigDecimal("10.00"), null, quantity, 0, UUID.randomUUID(), true));
    }

    private StockItemRequestDTO item(Product product, Integer quantity) {
        return new StockItemRequestDTO(product.getId(), quantity);
    }

    private void assertStock(Product product, int available, int reserved) {
        Product persisted = products.findById(product.getId()).orElseThrow();
        assertThat(persisted.getQuantityAvailable()).isEqualTo(available);
        assertThat(persisted.getQuantityReserved()).isEqualTo(reserved);
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
}
