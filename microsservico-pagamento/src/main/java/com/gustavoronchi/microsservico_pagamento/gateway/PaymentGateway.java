package com.gustavoronchi.microsservico_pagamento.gateway;

import com.gustavoronchi.microsservico_pagamento.enums.PaymentStatus;

import java.math.BigDecimal;
import java.util.UUID;

public interface PaymentGateway {
    PaymentStatus process(UUID idempotencyKey, BigDecimal amount, String currency);
}
