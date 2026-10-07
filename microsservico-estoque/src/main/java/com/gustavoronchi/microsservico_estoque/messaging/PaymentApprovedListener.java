package com.gustavoronchi.microsservico_estoque.messaging;

import com.gustavoronchi.microsservico_estoque.config.RabbitMQConfig;
import com.gustavoronchi.microsservico_estoque.exception.InvalidStockRequestException;
import com.gustavoronchi.microsservico_estoque.service.StockService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class PaymentApprovedListener {

    private static final Logger log = LoggerFactory.getLogger(PaymentApprovedListener.class);
    private final StockService stockService;

    public PaymentApprovedListener(StockService stockService) {
        this.stockService = stockService;
    }

    @RabbitListener(queues = RabbitMQConfig.STOCK_PAYMENT_APPROVED_QUEUE)
    public void hearPaymentApproved(PaymentApprovedEvent event) {
        if (event == null || event.getEventId() == null || event.getOrderId() == null
                || event.getOccurredAt() == null || event.getPaymentId() == null || event.getReservationId() == null
                || event.getAmount() == null || event.getAmount().signum() < 0 || !"BRL".equals(event.getCurrency())) {
            throw new InvalidStockRequestException("Evento de aprovação de pagamento inválido.");
        }
        log.info("Processando pagamento aprovado: orderId={}, eventId={}", event.getOrderId(), event.getEventId());
        stockService.confirm(event.getOrderId(), event.getReservationId());
        log.info("Evento de aprovação processado: orderId={}, eventId={}", event.getOrderId(), event.getEventId());
    }
}
