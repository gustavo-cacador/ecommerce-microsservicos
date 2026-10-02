package com.gustavoronchi.microsservico_pagamento.service;

import com.gustavoronchi.microsservico_pagamento.domain.entities.Payment;
import com.gustavoronchi.microsservico_pagamento.domain.repositories.PaymentRepository;
import com.gustavoronchi.microsservico_pagamento.dto.PaymentRequestDTO;
import com.gustavoronchi.microsservico_pagamento.dto.PaymentResponseDTO;
import com.gustavoronchi.microsservico_pagamento.enums.PaymentStatus;
import com.gustavoronchi.microsservico_pagamento.exception.InvalidPaymentRequestException;
import com.gustavoronchi.microsservico_pagamento.gateway.PaymentGateway;
import com.gustavoronchi.microsservico_pagamento.messaging.StockReservedEvent;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

@Service
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final PaymentGateway paymentGateway;
    private final TransactionTemplate transactionTemplate;

    public PaymentService(PaymentRepository paymentRepository, PaymentGateway paymentGateway,
                          TransactionTemplate transactionTemplate) {
        this.paymentRepository = paymentRepository;
        this.paymentGateway = paymentGateway;
        this.transactionTemplate = transactionTemplate;
    }

    public PaymentResponseDTO process(PaymentRequestDTO dto) {
        if (dto == null) {
            throw new InvalidPaymentRequestException("Requisição de pagamento inválida.");
        }
        return process(dto.getOrderId(), null, dto.getAmount(), "BRL");
    }

    public PaymentResponseDTO process(StockReservedEvent event) {
        return process(event.getOrderId(), event.getReservationId(), event.getAmount(), event.getCurrency());
    }

    private PaymentResponseDTO process(UUID orderId, UUID reservationId, BigDecimal amount, String currency) {
        if (orderId == null || amount == null || amount.signum() < 0 || !"BRL".equals(currency)) {
            throw new InvalidPaymentRequestException("Dados de pagamento inválidos.");
        }
        Payment payment = preparePayment(orderId, reservationId, amount, currency);
        if (payment.getStatus() != PaymentStatus.PENDING) {
            return new PaymentResponseDTO(payment);
        }

        PaymentStatus result = paymentGateway.process(orderId, amount, currency);
        if (result != PaymentStatus.APPROVED && result != PaymentStatus.REFUSED) {
            throw new IllegalStateException("Gateway não retornou um resultado definitivo.");
        }
        return transactionTemplate.execute(transaction -> {
            Payment current = paymentRepository.findByOrderIdForUpdate(orderId).orElseThrow();
            if (current.getStatus() == PaymentStatus.PENDING) {
                current.setStatus(result);
            }
            return new PaymentResponseDTO(current);
        });
    }

    private Payment preparePayment(UUID orderId, UUID reservationId, BigDecimal amount, String currency) {
        try {
            return transactionTemplate.execute(transaction -> {
                Payment existing = paymentRepository.findByOrderIdForUpdate(orderId).orElse(null);
                if (existing != null) {
                    validatePayment(existing, reservationId, amount, currency);
                    return existing;
                }
                Payment payment = new Payment();
                payment.setOrderId(orderId);
                payment.setReservationId(reservationId);
                payment.setAmount(amount);
                payment.setCurrency(currency);
                payment.setStatus(PaymentStatus.PENDING);
                payment.setCreatedAt(Instant.now().truncatedTo(ChronoUnit.MICROS));
                return paymentRepository.saveAndFlush(payment);
            });
        } catch (DataIntegrityViolationException exception) {
            // O lock não protege uma linha inexistente; a restrição única resolve a primeira inserção concorrente.
            return transactionTemplate.execute(transaction -> {
                Payment existing = paymentRepository.findByOrderIdForUpdate(orderId).orElseThrow(() -> exception);
                validatePayment(existing, reservationId, amount, currency);
                return existing;
            });
        }
    }

    private void validatePayment(Payment payment, UUID reservationId, BigDecimal amount, String currency) {
        if (!Objects.equals(payment.getReservationId(), reservationId)
                || payment.getAmount().compareTo(amount) != 0 || !payment.getCurrency().equals(currency)) {
            throw new InvalidPaymentRequestException("Pagamento existente possui dados diferentes para o pedido: "
                    + payment.getOrderId());
        }
    }
}
