package com.gustavoronchi.microsservico_pagamento.gateway;

import com.gustavoronchi.microsservico_pagamento.enums.PaymentStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;

@Component
public class FakePaymentGateway implements PaymentGateway {

    private final boolean simulateTimeout;

    public FakePaymentGateway(@Value("${payment.gateway.simulate-timeout:false}") boolean simulateTimeout) {
        this.simulateTimeout = simulateTimeout;
    }

    @Override
    public PaymentStatus process(UUID idempotencyKey, BigDecimal amount, String currency) {
        if (simulateTimeout) {
            throw new PaymentGatewayUnavailableException("Timeout simulado do gateway de pagamento.");
        }
        // A simulação é determinística; no provedor real, enviar também a chave de idempotência.
        return amount.compareTo(new BigDecimal("500.00")) <= 0 ? PaymentStatus.APPROVED : PaymentStatus.REFUSED;
    }
}
