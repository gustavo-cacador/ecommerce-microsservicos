package com.gustavoronchi.microsservico_pagamento.resource;

import com.gustavoronchi.microsservico_pagamento.dto.PaymentRequestDTO;
import com.gustavoronchi.microsservico_pagamento.dto.PaymentResponseDTO;
import com.gustavoronchi.microsservico_pagamento.service.PaymentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("payments")
public class PaymentResource {

    private final PaymentService paymentService;

    public PaymentResource(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping
    public ResponseEntity<PaymentResponseDTO> process(@RequestBody PaymentRequestDTO dto) {
        return ResponseEntity.ok(paymentService.process(dto));
    }
}
