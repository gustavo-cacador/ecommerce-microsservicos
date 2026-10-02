package com.gustavoronchi.microsservico_pagamento.gateway;

import com.gustavoronchi.microsservico_pagamento.enums.PaymentStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FakePaymentGatewayTests {

    @Test
    void approvesUpToFiveHundredAndRefusesAboveWithRepeatableResult() {
        FakePaymentGateway gateway = new FakePaymentGateway(false);
        UUID key = UUID.randomUUID();
        assertThat(gateway.process(key, new BigDecimal("499.80"), "BRL")).isEqualTo(PaymentStatus.APPROVED);
        assertThat(gateway.process(key, new BigDecimal("500.00"), "BRL")).isEqualTo(PaymentStatus.APPROVED);
        assertThat(gateway.process(key, new BigDecimal("500.01"), "BRL")).isEqualTo(PaymentStatus.REFUSED);
        assertThat(gateway.process(key, new BigDecimal("500.01"), "BRL")).isEqualTo(PaymentStatus.REFUSED);
    }

    @Test
    void simulatedTimeoutDoesNotReturnRefusal() {
        assertThatThrownBy(() -> new FakePaymentGateway(true)
                .process(UUID.randomUUID(), new BigDecimal("100.00"), "BRL"))
                .isInstanceOf(PaymentGatewayUnavailableException.class);
    }
}
