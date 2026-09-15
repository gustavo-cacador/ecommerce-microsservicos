package com.gustavoronchi.microsservico_pagamento.domain.repositories;

import com.gustavoronchi.microsservico_pagamento.domain.entities.Payment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {
}
