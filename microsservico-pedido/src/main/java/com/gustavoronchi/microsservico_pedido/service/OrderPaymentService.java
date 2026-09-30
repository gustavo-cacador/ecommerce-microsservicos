package com.gustavoronchi.microsservico_pedido.service;

import com.gustavoronchi.microsservico_pedido.config.RabbitMQConfig;
import com.gustavoronchi.microsservico_pedido.domain.entities.Order;
import com.gustavoronchi.microsservico_pedido.domain.entities.OutboxEvent;
import com.gustavoronchi.microsservico_pedido.domain.repository.OrderRepository;
import com.gustavoronchi.microsservico_pedido.domain.repository.OutboxEventRepository;
import com.gustavoronchi.microsservico_pedido.dto.OrderResponseDTO;
import com.gustavoronchi.microsservico_pedido.dto.StockItemRequestDTO;
import com.gustavoronchi.microsservico_pedido.enums.StatusOrder;
import com.gustavoronchi.microsservico_pedido.exception.OrderNotFoundException;
import com.gustavoronchi.microsservico_pedido.messaging.StockActionMessage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
public class OrderPaymentService {

    private final OrderRepository orderRepository;
    private final OutboxEventRepository outboxRepository;
    private final JsonMapper jsonMapper;

    public OrderPaymentService(OrderRepository orderRepository, OutboxEventRepository outboxRepository, JsonMapper jsonMapper) {
        this.orderRepository = orderRepository;
        this.outboxRepository = outboxRepository;
        this.jsonMapper = jsonMapper;
    }

    @Transactional
    public OrderResponseDTO approvePayment(UUID orderId) {
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new OrderNotFoundException("Pedido com id: " + orderId + " não encontrado."));

        if (order.getStatus() == StatusOrder.PAID) {
            return new OrderResponseDTO(order);
        }
        if (order.getStatus() != StatusOrder.WAITING_PAYMENT) {
            throw new IllegalStateException("Pedido não está aguardando pagamento: " + orderId);
        }

        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        order.setStatus(StatusOrder.PAID);
        order.setUpdatedAt(now);

        OutboxEvent event = new OutboxEvent();
        event.setOrderId(orderId);
        event.setExchange(RabbitMQConfig.STOCK_CONFIRM_EXCHANGE);
        event.setRoutingKey("");
        event.setOccurredAt(now);

        StockActionMessage message = new StockActionMessage(orderId, order.getItems().stream()
                .map(item -> new StockItemRequestDTO(item.getProductId(), item.getQuantity()))
                .toList());
        message.setEventId(event.getEventId());
        message.setOccurredAt(now);
        event.setPayload(jsonMapper.writeValueAsString(message));
        outboxRepository.save(event);

        return new OrderResponseDTO(order);
    }
}
