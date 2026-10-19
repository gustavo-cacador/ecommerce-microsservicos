package com.gustavoronchi.microsservico_pagamento.gateway;

import com.gustavoronchi.microsservico_pagamento.enums.PaymentStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class FakePaymentGateway implements PaymentGateway {

    private final boolean simulateTimeout;
    private final int failuresBeforeResponse;
    // Contadores apenas da simulação; reiniciam com a aplicação.
    private final ConcurrentHashMap<UUID, AtomicInteger> failuresByOrder = new ConcurrentHashMap<>();

    public FakePaymentGateway(@Value("${payment.gateway.simulate-timeout:false}") boolean simulateTimeout,
                              @Value("${payment.gateway.failures-before-response:0}") int failuresBeforeResponse) {
        if (failuresBeforeResponse < 0) {
            throw new IllegalArgumentException("A quantidade de falhas simuladas não pode ser negativa.");
        }
        this.simulateTimeout = simulateTimeout;
        this.failuresBeforeResponse = failuresBeforeResponse;
    }

    @Override
    public PaymentStatus process(UUID idempotencyKey, BigDecimal amount, String currency) {
        if (simulateTimeout) {
            throw new PaymentGatewayUnavailableException("Timeout simulado do gateway de pagamento.");
        }
        if (failuresBeforeResponse > 0) {
            AtomicInteger failures = failuresByOrder.computeIfAbsent(idempotencyKey, key -> new AtomicInteger());
            int previousFailures = failures.getAndUpdate(count -> count < failuresBeforeResponse ? count + 1 : count);
            if (previousFailures < failuresBeforeResponse) {
                throw new PaymentGatewayUnavailableException("Falha temporária simulada do gateway de pagamento.");
            }
        }
        // A simulação é determinística; no provedor real, enviar também a chave de idempotência.
        return amount.compareTo(new BigDecimal("500.00")) <= 0 ? PaymentStatus.APPROVED : PaymentStatus.REFUSED;
    }
}
