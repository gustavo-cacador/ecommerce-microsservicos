package com.gustavoronchi.microsservico_estoque.messaging;

import com.gustavoronchi.microsservico_estoque.config.RabbitMQConfig;
import com.gustavoronchi.microsservico_estoque.service.StockService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class StockActionListener {

    private static final Logger log = LoggerFactory.getLogger(StockActionListener.class);

    private final StockService stockService;

    public StockActionListener(StockService stockService) {
        this.stockService = stockService;
    }

    @RabbitListener(queues = RabbitMQConfig.STOCK_CONFIRM_QUEUE)
    public void hearConfirmation(StockActionMessage message) {
        log.info("Confirmando estoque para o pedido {}", message.getOrderId());
        stockService.confirm(message.getItems());
    }

    @RabbitListener(queues = RabbitMQConfig.STOCK_RELEASE_QUEUE)
    public void hearRelease(StockActionMessage message) {
        log.info("Liberando estoque para o pedido {}", message.getOrderId());
        stockService.release(message.getItems());
    }
}
