package com.gustavoronchi.microsservico_pedido.messaging;

import com.gustavoronchi.microsservico_pedido.config.RabbitMQConfig;
import com.gustavoronchi.microsservico_pedido.exception.InvalidOrderRequestException;
import com.gustavoronchi.microsservico_pedido.service.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class StockReservedListener {

    private static final Logger log = LoggerFactory.getLogger(StockReservedListener.class);

    private final OrderService orderService;

    public StockReservedListener(OrderService orderService) {
        this.orderService = orderService;
    }

    @RabbitListener(queues = RabbitMQConfig.ORDER_STOCK_RESERVED_QUEUE)
    public void hearStockReserved(StockReservedEvent event) {
        if (event == null || event.getEventId() == null || event.getOrderId() == null
                || event.getOccurredAt() == null || event.getReservationId() == null || event.getAmount() == null
                || event.getAmount().signum() < 0 || !"BRL".equals(event.getCurrency())) {
            throw new InvalidOrderRequestException("Evento de reserva de estoque inválido.");
        }
        log.info("Processando reserva concluída: orderId={}, eventId={}", event.getOrderId(), event.getEventId());
        orderService.waitForPayment(event.getOrderId(), event.getAmount());
        log.info("Evento de reserva processado: orderId={}, eventId={}", event.getOrderId(), event.getEventId());
    }
}
