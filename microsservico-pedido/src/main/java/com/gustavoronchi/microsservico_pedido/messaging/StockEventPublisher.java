package com.gustavoronchi.microsservico_pedido.messaging;

import com.gustavoronchi.microsservico_pedido.config.RabbitMQConfig;
import com.gustavoronchi.microsservico_pedido.dto.StockItemRequestDTO;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
public class StockEventPublisher {

    private final RabbitTemplate rabbitTemplate;

    public StockEventPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public void publishConfirm(UUID orderId, List<StockItemRequestDTO> items) {
        rabbitTemplate.convertAndSend(RabbitMQConfig.STOCK_CONFIRM_EXCHANGE, "", new StockActionMessage(orderId, items));
    }

    public void publishRelease(UUID orderId, List<StockItemRequestDTO> items) {
        rabbitTemplate.convertAndSend(RabbitMQConfig.STOCK_RELEASE_EXCHANGE, "", new StockActionMessage(orderId, items));
    }
}
