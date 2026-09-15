package com.gustavoronchi.microsservico_pagamento.service;

import com.gustavoronchi.microsservico_pagamento.domain.entities.Payment;
import com.gustavoronchi.microsservico_pagamento.domain.repositories.PaymentRepository;
import com.gustavoronchi.microsservico_pagamento.dto.PaymentRequestDTO;
import com.gustavoronchi.microsservico_pagamento.dto.PaymentResponseDTO;
import com.gustavoronchi.microsservico_pagamento.enums.PaymentStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Random;

@Service
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final Random random = new Random();

    public PaymentService(PaymentRepository paymentRepository) {
        this.paymentRepository = paymentRepository;
    }

    @Transactional
    public PaymentResponseDTO process(PaymentRequestDTO dto) {
        Payment payment = new Payment();
        payment.setOrderId(dto.getOrderId());
        payment.setAmount(dto.getAmount());
        payment.setCreatedAt(Instant.now());

        // substituir por integração real com gateway de pagamento.
        // simulação: ~80% de aprovação, só para permitir testar o fluxo completo.
        boolean aprovado = random.nextInt(10) > 1;
        payment.setStatus(aprovado ? PaymentStatus.APPROVED : PaymentStatus.REFUSED);

        Payment salvo = paymentRepository.save(payment);
        return new PaymentResponseDTO(salvo);
    }
}
