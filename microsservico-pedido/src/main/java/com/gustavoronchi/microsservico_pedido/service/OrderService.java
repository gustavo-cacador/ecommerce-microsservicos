package com.gustavoronchi.microsservico_pedido.service;

import com.gustavoronchi.microsservico_pedido.client.StockClient;
import com.gustavoronchi.microsservico_pedido.config.RabbitMQConfig;
import com.gustavoronchi.microsservico_pedido.domain.entities.Order;
import com.gustavoronchi.microsservico_pedido.domain.entities.OrderItem;
import com.gustavoronchi.microsservico_pedido.domain.entities.OutboxEvent;
import com.gustavoronchi.microsservico_pedido.domain.repository.OrderRepository;
import com.gustavoronchi.microsservico_pedido.domain.repository.OutboxEventRepository;
import com.gustavoronchi.microsservico_pedido.dto.*;
import com.gustavoronchi.microsservico_pedido.enums.StatusOrder;
import com.gustavoronchi.microsservico_pedido.exception.InvalidOrderRequestException;
import com.gustavoronchi.microsservico_pedido.exception.InsufficientStockException;
import com.gustavoronchi.microsservico_pedido.exception.OrderNotFoundException;
import com.gustavoronchi.microsservico_pedido.exception.StockUnavailableException;
import com.gustavoronchi.microsservico_pedido.messaging.OrderCreatedEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final StockClient stockClient;
    private final OutboxEventRepository outboxRepository;
    private final JsonMapper jsonMapper;
    private final TransactionTemplate transactionTemplate;

    public OrderService(OrderRepository orderRepository, StockClient stockClient, OutboxEventRepository outboxRepository, JsonMapper jsonMapper, TransactionTemplate transactionTemplate) {
        this.orderRepository = orderRepository;
        this.stockClient = stockClient;
        this.outboxRepository = outboxRepository;
        this.jsonMapper = jsonMapper;
        this.transactionTemplate = transactionTemplate;
    }

    @Transactional(readOnly = true)
    public OrderResponseDTO findById(UUID id) {
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new OrderNotFoundException("Pedido com id: " + id + " não encontrado."));
        return new OrderResponseDTO(order);
    }

    @Transactional(readOnly = true)
    public Page<OrderResponseDTO> findAll(Pageable pageable) {
        return orderRepository.findAll(pageable)
                .map(OrderResponseDTO::new);
    }

    @Transactional
    public OrderResponseDTO updateStatus(UUID id, StatusOrder newStatus) {
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new OrderNotFoundException("Pedido com id: " + id + " não encontrado."));
        order.setStatus(newStatus);
        order.setUpdatedAt(Instant.now());
        Order updated = orderRepository.save(order);
        return new OrderResponseDTO(updated);
    }

    @Transactional
    public void waitForPayment(UUID orderId, BigDecimal amount) {
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new OrderNotFoundException("Pedido com id: " + orderId + " não encontrado."));
        if (amount == null || order.getTotalValue().compareTo(amount) != 0) {
            throw new InvalidOrderRequestException("Valor da reserva diferente do total do pedido: " + orderId);
        }
        if (order.getStatus() == StatusOrder.CREATED) {
            order.setStatus(StatusOrder.WAITING_PAYMENT);
            order.setUpdatedAt(Instant.now().truncatedTo(ChronoUnit.MICROS));
        }
    }

    @Transactional
    public void approvePayment(UUID orderId, BigDecimal amount) {
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new OrderNotFoundException("Pedido com id: " + orderId + " não encontrado."));
        if (amount == null || order.getTotalValue().compareTo(amount) != 0) {
            throw new InvalidOrderRequestException("Valor do pagamento diferente do total do pedido: " + orderId);
        }
        if (order.getStatus() == StatusOrder.CREATED || order.getStatus() == StatusOrder.WAITING_PAYMENT) {
            order.setStatus(StatusOrder.PAID);
            order.setUpdatedAt(Instant.now().truncatedTo(ChronoUnit.MICROS));
        }
    }

    @Transactional
    public void cancelForStockFailure(UUID orderId) {
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new OrderNotFoundException("Pedido com id: " + orderId + " não encontrado."));
        if (order.getStatus() == StatusOrder.CREATED) {
            order.setStatus(StatusOrder.CANCELED);
            order.setUpdatedAt(Instant.now().truncatedTo(ChronoUnit.MICROS));
        }
    }

    public OrderResponseDTO createOrder(OrderRequestDTO request) {
        if (request == null || request.getClientId() == null || request.getItems() == null || request.getItems().isEmpty()
                || request.getItems().stream().anyMatch(item -> item == null || item.getProductId() == null
                        || item.getQuantity() == null || item.getQuantity() <= 0)) {
            throw new InvalidOrderRequestException("Informe cliente e itens com produto e quantidade positiva.");
        }

        Map<UUID, Integer> quantities = new LinkedHashMap<>();
        for (OrderItemRequestDTO item : request.getItems()) {
            try {
                quantities.merge(item.getProductId(), item.getQuantity(), Math::addExact);
            } catch (ArithmeticException ex) {
                throw new InvalidOrderRequestException("Quantidade total excede o limite para o produto " + item.getProductId());
            }
        }

        List<ProductPriceDTO> prices;
        try {
            prices = stockClient.findPrices(List.copyOf(quantities.keySet()));
        } catch (HttpClientErrorException.NotFound ex) {
            throw new InvalidOrderRequestException("Pedido contém produto inexistente ou inativo.");
        } catch (RestClientException ex) {
            throw new StockUnavailableException("Não foi possível consultar os preços: " + ex.getMessage());
        }
        if (prices == null) {
            throw new StockUnavailableException("Catálogo retornou uma resposta vazia.");
        }
        for (ProductPriceDTO product : prices) {
            Integer requested = quantities.get(product.getProductId());
            if (requested == null) {
                continue;
            }
            Integer available = product.getAvailableStock();
            if (available == null || available < 0) {
                throw new StockUnavailableException("Catálogo não retornou disponibilidade válida para o produto " + product.getProductId());
            }
            if (requested > available) {
                String message = available == 0
                        ? "Produto fora de estoque: " + product.getProductId()
                        : "Estoque insuficiente para o produto " + product.getProductId() + ". Disponível: " + available;
                throw new InsufficientStockException(message);
            }
        }
        return transactionTemplate.execute(status -> create(request, prices));
    }

    private OrderResponseDTO create(OrderRequestDTO request, List<ProductPriceDTO> prices) {
        Map<UUID, BigDecimal> pricesByProduct = prices.stream()
                .collect(Collectors.toMap(ProductPriceDTO::getProductId, ProductPriceDTO::getPrice));
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Order order = new Order();
        order.setClientId(request.getClientId());
        order.setStatus(StatusOrder.CREATED);
        order.setCreatedAt(now);
        order.setUpdatedAt(now);

        for (OrderItemRequestDTO requested : request.getItems()) {
            BigDecimal price = pricesByProduct.get(requested.getProductId());
            if (price == null) {
                throw new IllegalStateException("Catálogo não retornou preço para o produto " + requested.getProductId());
            }
            OrderItem item = new OrderItem();
            item.setOrder(order);
            item.setProductId(requested.getProductId());
            item.setQuantity(requested.getQuantity());
            item.setPrice(price);
            order.getItems().add(item);
        }
        order.setTotalValue(order.getItems().stream()
                .map(item -> item.getPrice().multiply(BigDecimal.valueOf(item.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        Order saved = orderRepository.save(order);

        OutboxEvent event = new OutboxEvent();
        event.setOrderId(saved.getId());
        event.setExchange(RabbitMQConfig.ORDER_CREATED_EXCHANGE);
        event.setRoutingKey("");
        event.setOccurredAt(now);
        OrderCreatedEvent message = new OrderCreatedEvent(event.getEventId(), saved.getId(), now,
                saved.getItems().stream()
                        .map(item -> new StockItemRequestDTO(item.getProductId(), item.getQuantity()))
                        .toList(),
                saved.getTotalValue(), "BRL");
        event.setPayload(jsonMapper.writeValueAsString(message));
        outboxRepository.save(event);

        return new OrderResponseDTO(saved);
    }
}
