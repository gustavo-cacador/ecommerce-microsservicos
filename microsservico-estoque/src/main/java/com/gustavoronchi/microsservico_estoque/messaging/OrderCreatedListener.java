package com.gustavoronchi.microsservico_estoque.messaging;

import com.gustavoronchi.microsservico_estoque.config.RabbitMQConfig;
import com.gustavoronchi.microsservico_estoque.dto.StockItemResponseDTO;
import com.gustavoronchi.microsservico_estoque.exception.InvalidStockRequestException;
import com.gustavoronchi.microsservico_estoque.service.StockService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class OrderCreatedListener {

    private static final Logger log = LoggerFactory.getLogger(OrderCreatedListener.class);

    private final StockService stockService;

    public OrderCreatedListener(StockService stockService) {
        this.stockService = stockService;
    }

    @RabbitListener(queues = RabbitMQConfig.STOCK_ORDER_CREATED_QUEUE)
    public void hearOrderCreated(OrderCreatedEvent event) {
        if (event == null || event.getEventId() == null || event.getOrderId() == null
                || event.getOccurredAt() == null || event.getAmount() == null
                || event.getAmount().signum() < 0 || !"BRL".equals(event.getCurrency())) {
            throw new InvalidStockRequestException("Evento de criação de pedido inválido.");
        }

        log.info("Reservando estoque: orderId={}, eventId={}", event.getOrderId(), event.getEventId());
        StockItemResponseDTO response = stockService.reserve(event.getOrderId(), event.getItems());
        if (!response.isSuccess()) {
            // Preserva a mensagem para inspeção até conectar StockReservationFailedEvent.
            throw new AmqpRejectAndDontRequeueException(response.getFailureReason());
        }
        log.info("Estoque reservado: orderId={}, eventId={}", event.getOrderId(), event.getEventId());
    }
}
