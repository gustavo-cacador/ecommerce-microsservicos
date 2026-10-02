package com.gustavoronchi.microsservico_pagamento.service;

import com.gustavoronchi.microsservico_pagamento.domain.entities.Payment;
import com.gustavoronchi.microsservico_pagamento.domain.repositories.PaymentRepository;
import com.gustavoronchi.microsservico_pagamento.enums.PaymentStatus;
import com.gustavoronchi.microsservico_pagamento.exception.InvalidPaymentRequestException;
import com.gustavoronchi.microsservico_pagamento.gateway.PaymentGateway;
import com.gustavoronchi.microsservico_pagamento.gateway.PaymentGatewayUnavailableException;
import com.gustavoronchi.microsservico_pagamento.messaging.PaymentListener;
import com.gustavoronchi.microsservico_pagamento.messaging.StockReservedEvent;
import com.gustavoronchi.microsservico_pagamento.resource.PaymentResource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DataJpaTest(showSql = false)
@Import({PaymentService.class, PaymentListener.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PaymentServiceTests {

    @Autowired
    private PaymentService service;
    @Autowired
    private PaymentListener listener;
    @MockitoSpyBean
    private PaymentRepository payments;
    @MockitoBean
    private PaymentGateway gateway;
    private StockReservedEvent event;

    @BeforeEach
    void setUp() {
        payments.deleteAll();
        event = new StockReservedEvent(UUID.randomUUID(), UUID.randomUUID(), Instant.now(),
                UUID.randomUUID(), new BigDecimal("499.80"), "BRL");
        when(gateway.process(any(), any(), any())).thenAnswer(invocation -> {
            assertPendingOutsideTransaction();
            return PaymentStatus.APPROVED;
        });
    }

    @Test
    void approvesAndSkipsDuplicateAfterCompletion() {
        listener.hearStockReserved(event);
        Payment first = onlyPayment();
        listener.hearStockReserved(event);
        Payment repeated = onlyPayment();

        assertThat(repeated.getStatus()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(repeated.getId()).isEqualTo(first.getId());
        assertThat(repeated.getCreatedAt()).isEqualTo(first.getCreatedAt());
        assertThat(repeated.getReservationId()).isEqualTo(event.getReservationId());
        assertThat(repeated.getCurrency()).isEqualTo("BRL");
        verify(gateway, times(1)).process(event.getOrderId(), event.getAmount(), "BRL");
    }

    @Test
    void persistsRefusalWithoutCallingGatewayAgainOnDuplicate() {
        doReturn(PaymentStatus.REFUSED).when(gateway).process(any(), any(), any());
        listener.hearStockReserved(event);
        listener.hearStockReserved(event);
        assertThat(onlyPayment().getStatus()).isEqualTo(PaymentStatus.REFUSED);
        verify(gateway, times(1)).process(event.getOrderId(), event.getAmount(), "BRL");
    }

    @Test
    void timeoutRemainsPendingAndRedeliveryResumesSamePayment() {
        doThrow(new PaymentGatewayUnavailableException("Timeout")).when(gateway).process(any(), any(), any());
        assertThatThrownBy(() -> listener.hearStockReserved(event))
                .isInstanceOf(PaymentGatewayUnavailableException.class);
        Payment pending = onlyPayment();
        assertThat(pending.getStatus()).isEqualTo(PaymentStatus.PENDING);

        doAnswer(invocation -> {
            assertPendingOutsideTransaction();
            return PaymentStatus.APPROVED;
        }).when(gateway).process(any(), any(), any());
        listener.hearStockReserved(event);
        assertThat(onlyPayment().getId()).isEqualTo(pending.getId());
        assertThat(onlyPayment().getStatus()).isEqualTo(PaymentStatus.APPROVED);
        verify(gateway, times(2)).process(event.getOrderId(), event.getAmount(), "BRL");
    }

    @Test
    void simultaneousFirstDeliveriesCreateOnePaymentAndUseTheSameProviderKey() throws Exception {
        CountDownLatch gatewayCalls = new CountDownLatch(2);
        doAnswer(invocation -> {
            assertPendingOutsideTransaction();
            gatewayCalls.countDown();
            assertThat(gatewayCalls.await(10, TimeUnit.SECONDS)).isTrue();
            return PaymentStatus.APPROVED;
        }).when(gateway).process(any(), any(), any());
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> listener.hearStockReserved(event));
            var second = executor.submit(() -> listener.hearStockReserved(event));
            first.get(15, TimeUnit.SECONDS);
            second.get(15, TimeUnit.SECONDS);
        }
        assertThat(onlyPayment().getStatus()).isEqualTo(PaymentStatus.APPROVED);
        verify(gateway, times(2)).process(event.getOrderId(), event.getAmount(), "BRL");
    }

    @Test
    void failureSavingApprovedResultLeavesPendingForRetryWithSameProviderKey() {
        doAnswer(invocation -> {
            assertPendingOutsideTransaction();
            doThrow(new DataAccessResourceFailureException("Banco indisponível"))
                    .when(payments).findByOrderIdForUpdate(event.getOrderId());
            return PaymentStatus.APPROVED;
        }).when(gateway).process(any(), any(), any());
        assertThatThrownBy(() -> listener.hearStockReserved(event))
                .isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(onlyPayment().getStatus()).isEqualTo(PaymentStatus.PENDING);
        reset(payments);
        doReturn(PaymentStatus.APPROVED).when(gateway).process(any(), any(), any());
        listener.hearStockReserved(event);
        assertThat(onlyPayment().getStatus()).isEqualTo(PaymentStatus.APPROVED);
        verify(gateway, times(2)).process(event.getOrderId(), event.getAmount(), "BRL");
    }

    @ParameterizedTest
    @ValueSource(strings = {"amount", "reservation", "currency"})
    void rejectsInconsistentDuplicate(String field) {
        listener.hearStockReserved(event);
        switch (field) {
            case "amount" -> event.setAmount(new BigDecimal("1.00"));
            case "reservation" -> event.setReservationId(UUID.randomUUID());
            case "currency" -> event.setCurrency("USD");
        }
        assertThatThrownBy(() -> listener.hearStockReserved(event)).isInstanceOf(InvalidPaymentRequestException.class);
        assertThat(onlyPayment().getStatus()).isEqualTo(PaymentStatus.APPROVED);
        verify(gateway, times(1)).process(any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"event", "eventId", "orderId", "occurredAt", "reservationId", "amount", "negative", "currency"})
    void rejectsInvalidEventBeforeCreatingPayment(String field) {
        switch (field) {
            case "event" -> event = null;
            case "eventId" -> event.setEventId(null);
            case "orderId" -> event.setOrderId(null);
            case "occurredAt" -> event.setOccurredAt(null);
            case "reservationId" -> event.setReservationId(null);
            case "amount" -> event.setAmount(null);
            case "negative" -> event.setAmount(new BigDecimal("-1.00"));
            case "currency" -> event.setCurrency(null);
        }
        assertThatThrownBy(() -> listener.hearStockReserved(event)).isInstanceOf(InvalidPaymentRequestException.class);
        assertThat(payments.count()).isZero();
        verifyNoInteractions(gateway);
    }

    @Test
    void unknownGatewayResultRemainsPending() {
        doReturn(PaymentStatus.PENDING).when(gateway).process(any(), any(), any());
        assertThatThrownBy(() -> listener.hearStockReserved(event)).isInstanceOf(IllegalStateException.class);
        assertThat(onlyPayment().getStatus()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    void existingHttpEndpointUsesSameShortTransactionsAndSinglePayment() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new PaymentResource(service)).build();
        String body = "{\"orderId\":\"" + event.getOrderId() + "\",\"amount\":499.80}";
        mvc.perform(post("/payments").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("status").value("APPROVED"));
        mvc.perform(post("/payments").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("status").value("APPROVED"));
        assertThat(payments.count()).isEqualTo(1);
        verify(gateway, times(1)).process(event.getOrderId(), event.getAmount(), "BRL");
    }

    private void assertPendingOutsideTransaction() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(onlyPayment().getStatus()).isEqualTo(PaymentStatus.PENDING);
    }

    private Payment onlyPayment() {
        assertThat(payments.count()).isEqualTo(1);
        return payments.findAll().getFirst();
    }
}
