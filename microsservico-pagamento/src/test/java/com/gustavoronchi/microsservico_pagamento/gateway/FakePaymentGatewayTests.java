package com.gustavoronchi.microsservico_pagamento.gateway;

import com.gustavoronchi.microsservico_pagamento.enums.PaymentStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FakePaymentGatewayTests {

    @Test
    void approvesUpToFiveHundredAndRefusesAboveWithRepeatableResult() {
        FakePaymentGateway gateway = new FakePaymentGateway(false, 0);
        UUID key = UUID.randomUUID();
        assertThat(gateway.process(key, new BigDecimal("499.80"), "BRL")).isEqualTo(PaymentStatus.APPROVED);
        assertThat(gateway.process(key, new BigDecimal("500.00"), "BRL")).isEqualTo(PaymentStatus.APPROVED);
        assertThat(gateway.process(key, new BigDecimal("500.01"), "BRL")).isEqualTo(PaymentStatus.REFUSED);
        assertThat(gateway.process(key, new BigDecimal("500.01"), "BRL")).isEqualTo(PaymentStatus.REFUSED);
    }

    @Test
    void simulatedTimeoutDoesNotReturnRefusal() {
        FakePaymentGateway gateway = new FakePaymentGateway(true, 2);
        UUID key = UUID.randomUUID();
        for (int attempt = 0; attempt < 4; attempt++) {
            assertThatThrownBy(() -> gateway.process(key, new BigDecimal("100.00"), "BRL"))
                    .isInstanceOf(PaymentGatewayUnavailableException.class);
        }
    }

    @ParameterizedTest
    @CsvSource({"499.80, APPROVED", "500.01, REFUSED"})
    void respondsAfterTwoFailuresAndDoesNotRestartFailuresForSameOrder(BigDecimal amount, PaymentStatus expected) {
        FakePaymentGateway gateway = new FakePaymentGateway(false, 2);
        UUID key = UUID.randomUUID();
        for (int attempt = 0; attempt < 2; attempt++) {
            assertThatThrownBy(() -> gateway.process(key, amount, "BRL"))
                    .isInstanceOf(PaymentGatewayUnavailableException.class);
        }
        assertThat(gateway.process(key, amount, "BRL")).isEqualTo(expected);
        assertThat(gateway.process(key, amount, "BRL")).isEqualTo(expected);
    }

    @Test
    void countsFailuresIndependentlyForEachOrder() {
        FakePaymentGateway gateway = new FakePaymentGateway(false, 1);
        UUID firstOrder = UUID.randomUUID();
        UUID secondOrder = UUID.randomUUID();
        assertThatThrownBy(() -> gateway.process(firstOrder, BigDecimal.TEN, "BRL"))
                .isInstanceOf(PaymentGatewayUnavailableException.class);
        assertThatThrownBy(() -> gateway.process(secondOrder, BigDecimal.TEN, "BRL"))
                .isInstanceOf(PaymentGatewayUnavailableException.class);
        assertThat(gateway.process(firstOrder, BigDecimal.TEN, "BRL")).isEqualTo(PaymentStatus.APPROVED);
        assertThat(gateway.process(secondOrder, BigDecimal.TEN, "BRL")).isEqualTo(PaymentStatus.APPROVED);
    }

    @Test
    void concurrentCallsShareTheSameFailureLimit() throws Exception {
        FakePaymentGateway gateway = new FakePaymentGateway(false, 2);
        UUID key = UUID.randomUUID();
        var calls = IntStream.range(0, 10).<Callable<Boolean>>mapToObj(index -> () -> {
            try {
                assertThat(gateway.process(key, BigDecimal.TEN, "BRL")).isEqualTo(PaymentStatus.APPROVED);
                return false;
            } catch (PaymentGatewayUnavailableException exception) {
                return true;
            }
        }).toList();

        try (var executor = Executors.newFixedThreadPool(4)) {
            int failures = 0;
            for (var result : executor.invokeAll(calls, 5, TimeUnit.SECONDS)) {
                if (result.get()) {
                    failures++;
                }
            }
            assertThat(failures).isEqualTo(2);
        }
    }

    @Test
    void rejectsNegativeFailureCount() {
        assertThatThrownBy(() -> new FakePaymentGateway(false, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
