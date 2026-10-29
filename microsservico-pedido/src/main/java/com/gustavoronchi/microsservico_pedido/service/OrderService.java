package com.gustavoronchi.microsservico_pedido.service;

import com.gustavoronchi.microsservico_pedido.client.StockClient;
import com.gustavoronchi.microsservico_pedido.config.RabbitMQConfig;
import com.gustavoronchi.microsservico_pedido.domain.entities.CatalogProduct;
import com.gustavoronchi.microsservico_pedido.domain.entities.Order;
import com.gustavoronchi.microsservico_pedido.domain.entities.OrderItem;
import com.gustavoronchi.microsservico_pedido.domain.entities.OutboxEvent;
import com.gustavoronchi.microsservico_pedido.domain.repository.OrderRepository;
import com.gustavoronchi.microsservico_pedido.domain.repository.CatalogProductRepository;
import com.gustavoronchi.microsservico_pedido.domain.repository.OutboxEventRepository;
import com.gustavoronchi.microsservico_pedido.dto.*;
import com.gustavoronchi.microsservico_pedido.enums.StatusOrder;
import com.gustavoronchi.microsservico_pedido.exception.InvalidOrderRequestException;
import com.gustavoronchi.microsservico_pedido.exception.IdempotencyConflictException;
import com.gustavoronchi.microsservico_pedido.exception.InsufficientStockException;
import com.gustavoronchi.microsservico_pedido.exception.OrderNotFoundException;
import com.gustavoronchi.microsservico_pedido.exception.StockUnavailableException;
import com.gustavoronchi.microsservico_pedido.messaging.OrderCreatedEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final StockClient stockClient;
    private final OutboxEventRepository outboxRepository;
    private final JsonMapper jsonMapper;
    private final TransactionTemplate transactionTemplate;
    private final CatalogProductRepository catalogProducts;
    private final Duration catalogMaxAge;

    public OrderService(OrderRepository orderRepository, StockClient stockClient, OutboxEventRepository outboxRepository, JsonMapper jsonMapper, TransactionTemplate transactionTemplate, CatalogProductRepository catalogProducts,
                        @Value("${catalog.fallback.max-age:PT24H}") Duration catalogMaxAge) {
        this.orderRepository = orderRepository;
        this.stockClient = stockClient;
        this.outboxRepository = outboxRepository;
        this.jsonMapper = jsonMapper;
        this.transactionTemplate = transactionTemplate;
        this.catalogProducts = catalogProducts;
        if (catalogMaxAge.isNegative() || catalogMaxAge.isZero()) {
            throw new IllegalArgumentException("A idade máxima do catálogo deve ser positiva.");
        }
        this.catalogMaxAge = catalogMaxAge;
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
    public void cancelForPaymentRefusal(UUID orderId, BigDecimal amount) {
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new OrderNotFoundException("Pedido com id: " + orderId + " não encontrado."));
        if (amount == null || order.getTotalValue().compareTo(amount) != 0) {
            throw new InvalidOrderRequestException("Valor do pagamento diferente do total do pedido: " + orderId);
        }
        if (order.getStatus() == StatusOrder.CREATED || order.getStatus() == StatusOrder.WAITING_PAYMENT) {
            order.setStatus(StatusOrder.CANCELED);
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
        return createOrder(request, null);
    }

    public OrderResponseDTO createOrder(OrderRequestDTO request, String idempotencyKey) {
        if (idempotencyKey != null && (idempotencyKey.isBlank() || idempotencyKey.length() > 128)) {
            throw new InvalidOrderRequestException("Idempotency-Key deve conter de 1 a 128 caracteres e não pode ser em branco.");
        }
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

        OrderResponseDTO existing = findIdempotentOrder(idempotencyKey, request.getClientId(), quantities);
        if (existing != null) {
            return existing;
        }

        List<ProductPriceDTO> prices = findPrices(quantities);
        try {
            return transactionTemplate.execute(status -> create(request, prices, idempotencyKey));
        } catch (DataIntegrityViolationException ex) {
            // A requisição concorrente pode ter gravado a mesma chave. Consultar após o rollback.
            OrderResponseDTO concurrent = findIdempotentOrder(idempotencyKey, request.getClientId(), quantities);
            if (concurrent != null) {
                return concurrent;
            }
            throw ex;
        }
    }

    private List<ProductPriceDTO> findPrices(Map<UUID, Integer> quantities) {
        List<ProductPriceDTO> prices;
        try {
            prices = stockClient.findPrices(List.copyOf(quantities.keySet()));
        } catch (HttpClientErrorException.NotFound ex) {
            throw new InvalidOrderRequestException("Pedido contém produto inexistente ou inativo.");
        } catch (RestClientException ex) {
            if (allowsCatalogFallback(ex)) {
                return findCatalogCopy(quantities.keySet());
            }
            throw new StockUnavailableException("Não foi possível consultar os preços: " + ex.getMessage());
        }
        if (prices == null) {
            throw new StockUnavailableException("Catálogo retornou uma resposta vazia.");
        }
        updateCatalogCopy(prices, quantities.keySet());
        for (ProductPriceDTO product : prices) {
            Integer requested = quantities.get(product.getProductId());
            if (requested == null) {
                continue;
            }
            Integer available = product.getAvailableStock();
            if (requested > available) {
                String message = available == 0
                        ? "Produto fora de estoque: " + product.getProductId()
                        : "Estoque insuficiente para o produto " + product.getProductId() + ". Disponível: " + available;
                throw new InsufficientStockException(message);
            }
        }
        return prices;
    }

    private boolean allowsCatalogFallback(RestClientException exception) {
        if (exception instanceof RestClientResponseException response) {
            return response.getStatusCode().is5xxServerError();
        }
        if (exception instanceof ResourceAccessException) {
            return true;
        }
        // O corpo pode falhar por transporte ou por codificação inválida; só o primeiro permite fallback.
        for (Throwable cause = exception.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException
                    || cause instanceof SocketException || cause instanceof UnknownHostException
                    || (cause.getClass() == IOException.class && cause.getCause() == null)) {
                return true;
            }
        }
        return false;
    }

    private List<ProductPriceDTO> findCatalogCopy(Set<UUID> productIds) {
        Instant oldestAllowed = Instant.now().minus(catalogMaxAge);
        List<CatalogProduct> copies = catalogProducts.findAllById(productIds);
        if (copies.size() != productIds.size() || copies.stream()
                .anyMatch(product -> product.getUpdatedAt().isBefore(oldestAllowed))) {
            throw new StockUnavailableException("Catálogo indisponível e cópia local ausente ou vencida.");
        }
        // Disponibilidade copiada não garante reserva; o estoque decidirá pelo fluxo assíncrono.
        return copies.stream().map(product -> new ProductPriceDTO(
                product.getProductId(), product.getPrice(), product.getAvailableStock())).toList();
    }

    private void updateCatalogCopy(List<ProductPriceDTO> prices, Set<UUID> requestedIds) {
        Set<UUID> returnedIds = new HashSet<>();
        for (ProductPriceDTO product : prices) {
            if (product == null || product.getProductId() == null || product.getPrice() == null
                    || product.getPrice().signum() < 0 || !returnedIds.add(product.getProductId())) {
                throw new StockUnavailableException("Catálogo retornou dados de produto inválidos.");
            }
            if (product.getAvailableStock() == null || product.getAvailableStock() < 0) {
                throw new StockUnavailableException("Catálogo não retornou disponibilidade válida para o produto " + product.getProductId());
            }
        }
        for (UUID productId : requestedIds) {
            if (!returnedIds.contains(productId)) {
                throw new IllegalStateException("Catálogo não retornou preço para o produto " + productId);
            }
        }

        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        List<CatalogProduct> copies = prices.stream()
                .sorted(java.util.Comparator.comparing(ProductPriceDTO::getProductId))
                .map(product -> new CatalogProduct(product.getProductId(), product.getPrice(), product.getAvailableStock(), now))
                .toList();
        try {
            transactionTemplate.executeWithoutResult(status -> catalogProducts.saveAllAndFlush(copies));
        } catch (DataIntegrityViolationException ex) {
            // Outra consulta pode ter inserido o produto. Repetir o upsert após o rollback.
            transactionTemplate.executeWithoutResult(status -> catalogProducts.saveAllAndFlush(copies));
        }
    }

    private OrderResponseDTO findIdempotentOrder(String idempotencyKey, UUID clientId, Map<UUID, Integer> quantities) {
        if (idempotencyKey == null) {
            return null;
        }
        return transactionTemplate.execute(status -> orderRepository.findByIdempotencyKey(idempotencyKey)
                .map(order -> {
                    Map<UUID, Integer> savedQuantities = order.getItems().stream()
                            .collect(Collectors.toMap(OrderItem::getProductId, OrderItem::getQuantity, Math::addExact));
                    if (!order.getClientId().equals(clientId) || !savedQuantities.equals(quantities)) {
                        throw new IdempotencyConflictException("Idempotency-Key já utilizada para um pedido com dados diferentes.");
                    }
                    return new OrderResponseDTO(order);
                }).orElse(null));
    }

    private OrderResponseDTO create(OrderRequestDTO request, List<ProductPriceDTO> prices, String idempotencyKey) {
        Map<UUID, BigDecimal> pricesByProduct = prices.stream()
                .collect(Collectors.toMap(ProductPriceDTO::getProductId, ProductPriceDTO::getPrice));
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Order order = new Order();
        order.setClientId(request.getClientId());
        order.setIdempotencyKey(idempotencyKey);
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
        Order saved = orderRepository.saveAndFlush(order);

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
