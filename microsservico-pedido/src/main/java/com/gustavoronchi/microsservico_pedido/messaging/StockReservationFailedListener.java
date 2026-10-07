package com.gustavoronchi.microsservico_pedido.messaging;

import com.gustavoronchi.microsservico_pedido.config.RabbitMQConfig;
import com.gustavoronchi.microsservico_pedido.exception.InvalidOrderRequestException;
import com.gustavoronchi.microsservico_pedido.service.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class StockReservationFailedListener {

    private static final Logger log = LoggerFactory.getLogger(StockReservationFailedListener.class);
    private final OrderService orderService;

    public StockReservationFailedListener(OrderService orderService) {
        this.orderService = orderService;
    }

    @RabbitListener(queues = RabbitMQConfig.ORDER_STOCK_RESERVATION_FAILED_QUEUE)
    public void hearStockReservationFailed(StockReservationFailedEvent event) {
        if (event == null || event.getEventId() == null || event.getOrderId() == null
                || event.getOccurredAt() == null || event.getFailureReason() == null || event.getFailureReason().isBlank()) {
            throw new InvalidOrderRequestException("Evento de falha de reserva de estoque inválido.");
        }
        log.info("Processando falha de reserva: orderId={}, eventId={}, motivo={}",
                event.getOrderId(), event.getEventId(), event.getFailureReason());
        orderService.cancelForStockFailure(event.getOrderId());
        log.info("Evento de falha de reserva processado: orderId={}, eventId={}", event.getOrderId(), event.getEventId());
    }
}
