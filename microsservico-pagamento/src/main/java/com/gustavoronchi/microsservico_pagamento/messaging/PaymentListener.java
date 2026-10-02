package com.gustavoronchi.microsservico_pagamento.messaging;

import com.gustavoronchi.microsservico_pagamento.config.RabbitMQConfig;
import com.gustavoronchi.microsservico_pagamento.exception.InvalidPaymentRequestException;
import com.gustavoronchi.microsservico_pagamento.service.PaymentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class PaymentListener {

    private static final Logger log = LoggerFactory.getLogger(PaymentListener.class);
    private final PaymentService paymentService;

    public PaymentListener(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @RabbitListener(queues = RabbitMQConfig.PAYMENT_STOCK_RESERVED_QUEUE)
    public void hearStockReserved(StockReservedEvent event) {
        if (event == null || event.getEventId() == null || event.getOrderId() == null
                || event.getOccurredAt() == null || event.getReservationId() == null) {
            throw new InvalidPaymentRequestException("Evento de reserva de estoque inválido.");
        }
        log.info("Processando pagamento: orderId={}, eventId={}", event.getOrderId(), event.getEventId());
        paymentService.process(event);
        log.info("Pagamento processado: orderId={}, eventId={}", event.getOrderId(), event.getEventId());
    }
}
