package com.gustavoronchi.microsservico_pedido.messaging;

import com.gustavoronchi.microsservico_pedido.config.RabbitMQConfig;
import com.gustavoronchi.microsservico_pedido.exception.InvalidOrderRequestException;
import com.gustavoronchi.microsservico_pedido.service.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class PaymentRefusedListener {

    private static final Logger log = LoggerFactory.getLogger(PaymentRefusedListener.class);
    private final OrderService orderService;

    public PaymentRefusedListener(OrderService orderService) {
        this.orderService = orderService;
    }

    @RabbitListener(queues = RabbitMQConfig.ORDER_PAYMENT_REFUSED_QUEUE)
    public void hearPaymentRefused(PaymentRefusedEvent event) {
        if (event == null || event.getEventId() == null || event.getOrderId() == null
                || event.getOccurredAt() == null || event.getPaymentId() == null || event.getReservationId() == null
                || event.getAmount() == null || event.getAmount().signum() < 0 || !"BRL".equals(event.getCurrency())) {
            throw new InvalidOrderRequestException("Evento de recusa de pagamento inválido.");
        }
        log.info("Processando pagamento recusado: orderId={}, eventId={}", event.getOrderId(), event.getEventId());
        orderService.cancelForPaymentRefusal(event.getOrderId(), event.getAmount());
        log.info("Evento de recusa processado: orderId={}, eventId={}", event.getOrderId(), event.getEventId());
    }
}
