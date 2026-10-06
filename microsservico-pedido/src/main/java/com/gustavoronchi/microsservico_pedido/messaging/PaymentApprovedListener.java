package com.gustavoronchi.microsservico_pedido.messaging;

import com.gustavoronchi.microsservico_pedido.config.RabbitMQConfig;
import com.gustavoronchi.microsservico_pedido.exception.InvalidOrderRequestException;
import com.gustavoronchi.microsservico_pedido.service.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class PaymentApprovedListener {

    private static final Logger log = LoggerFactory.getLogger(PaymentApprovedListener.class);
    private final OrderService orderService;

    public PaymentApprovedListener(OrderService orderService) {
        this.orderService = orderService;
    }

    @RabbitListener(queues = RabbitMQConfig.ORDER_PAYMENT_APPROVED_QUEUE)
    public void hearPaymentApproved(PaymentApprovedEvent event) {
        if (event == null || event.getEventId() == null || event.getOrderId() == null
                || event.getOccurredAt() == null || event.getPaymentId() == null || event.getReservationId() == null
                || event.getAmount() == null || event.getAmount().signum() < 0 || !"BRL".equals(event.getCurrency())) {
            throw new InvalidOrderRequestException("Evento de aprovação de pagamento inválido.");
        }
        log.info("Processando pagamento aprovado: orderId={}, eventId={}", event.getOrderId(), event.getEventId());
        orderService.approvePayment(event.getOrderId(), event.getAmount());
        log.info("Evento de aprovação processado: orderId={}, eventId={}", event.getOrderId(), event.getEventId());
    }
}
