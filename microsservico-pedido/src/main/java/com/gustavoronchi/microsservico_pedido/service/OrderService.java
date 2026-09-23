package com.gustavoronchi.microsservico_pedido.service;

import com.gustavoronchi.microsservico_pedido.client.*;
import com.gustavoronchi.microsservico_pedido.domain.entities.Order;
import com.gustavoronchi.microsservico_pedido.domain.entities.OrderItem;
import com.gustavoronchi.microsservico_pedido.domain.repository.OrderRepository;
import com.gustavoronchi.microsservico_pedido.dto.*;
import com.gustavoronchi.microsservico_pedido.enums.StatusOrder;
import com.gustavoronchi.microsservico_pedido.exception.OrderNotFoundException;
import com.gustavoronchi.microsservico_pedido.exception.PaymentUnavailableException;
import com.gustavoronchi.microsservico_pedido.exception.StockUnavailableException;
import com.gustavoronchi.microsservico_pedido.messaging.StockEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final StockClient stockClient;
    private final PaymentClient paymentClient;
    private final StockEventPublisher stockEventPublisher;

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    public OrderService(OrderRepository orderRepository, StockClient stockClient, PaymentClient paymentClient, StockEventPublisher stockEventPublisher) {
        this.orderRepository = orderRepository;
        this.stockClient = stockClient;
        this.paymentClient = paymentClient;
        this.stockEventPublisher = stockEventPublisher;
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

    public OrderResponseDTO createOrder(OrderRequestDTO orderRequestDTO) {

        List<StockItemRequestDTO> itemsToReserve = orderRequestDTO.getItems()
                .stream()
                .map(item -> new StockItemRequestDTO(item.getProductId(), item.getQuantity()))
                .toList();

        // reserva, se falhar aqui, nada foi comprometido ainda — não precisa compensar.
        StockReserveResponseDTO stockResponse = reserveStock(itemsToReserve);

        //  monta e salva o pedido, se falhar, o estoque já foi reservado e precisa compensar.
        Order order;
        try {
            order = createOrderEntity(orderRequestDTO, stockResponse);
        } catch (Exception ex) {
            log.error("Erro ao montar/salvar pedido. Revertendo reserva de estoque...", ex);
            releaseStock(itemsToReserve, null);
            throw new RuntimeException("Erro ao processar pedido. A reserva de estoque foi revertida.", ex);
        }

        // processa o pagamento.
        PaymentResponseDTO paymentResponse;
        try {
            paymentResponse = paymentClient.process(new PaymentRequestDTO(order.getId(), order.getTotalValue()));
        } catch (RestClientException ex) {
            log.error("Erro ao contatar serviço de pagamento para o pedido {}", order.getId(), ex);

            releaseStock(itemsToReserve, order.getId());

            order.setStatus(StatusOrder.DECLINED_PAYMENT);
            order.setUpdatedAt(Instant.now());
            orderRepository.save(order);

            throw new PaymentUnavailableException("Não foi possível contatar o serviço de pagamento: " + ex.getMessage());
        }

        // resultado do pagamento já é definitivo
        if ("APPROVED".equals(paymentResponse.getStatus())) {
            order.setStatus(StatusOrder.PAID);
            confirmStock(itemsToReserve, order.getId());
        } else {
            order.setStatus(StatusOrder.DECLINED_PAYMENT);
            releaseStock(itemsToReserve, order.getId());
        }

        order.setUpdatedAt(Instant.now());
        Order updatedOrder = orderRepository.save(order);

        return new OrderResponseDTO(updatedOrder);
    }

    private StockReserveResponseDTO reserveStock(List<StockItemRequestDTO> itemsToReserve) {
        try {
            StockReserveResponseDTO response = stockClient.reserve(itemsToReserve);

            if (!response.isSuccess()) {
                throw new StockUnavailableException(response.getFailureReason());
            }

            return response;

        } catch (RestClientException ex) {
            throw new StockUnavailableException("Não foi possível contatar o serviço de estoque: " + ex.getMessage());
        }
    }

    private Order createOrderEntity(OrderRequestDTO orderRequestDTO, StockReserveResponseDTO stockResponse) {
        Map<UUID, BigDecimal> pricesByProduct = stockResponse
                .getItems()
                .stream()
                .collect(Collectors.toMap(ReservedItemDTO::getProductId, ReservedItemDTO::getPrice));

        Order order = new Order();
        order.setClientId(orderRequestDTO.getClientId());
        order.setStatus(StatusOrder.WAITING_PAYMENT);
        order.setCreatedAt(Instant.now());
        order.setUpdatedAt(Instant.now());

        for (OrderItemRequestDTO itemRequestDTO : orderRequestDTO.getItems()) {
            BigDecimal price = pricesByProduct.get(itemRequestDTO.getProductId());

            if (price == null) {
                throw new IllegalStateException(
                        "Estoque não retornou preço para o produto " + itemRequestDTO.getProductId() +
                                " — resposta de reserva inconsistente."
                );
            }

            OrderItem orderItem = new OrderItem();
            orderItem.setOrder(order);
            orderItem.setProductId(itemRequestDTO.getProductId());
            orderItem.setQuantity(itemRequestDTO.getQuantity());
            orderItem.setPrice(price);
            order.getItems().add(orderItem);
        }

        BigDecimal total = order.getItems()
                .stream()
                .map(item -> item.getPrice().multiply(BigDecimal.valueOf(item.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        order.setTotalValue(total);

        return orderRepository.save(order);
    }

    private void releaseStock(List<StockItemRequestDTO> items, UUID orderId) {
        try {
            stockEventPublisher.publishRelease(orderId, items);
            log.info("Evento de liberação de estoque publicado para o pedido {}.", orderId);
        } catch (Exception ex) {
            log.error("FALHA GRAVE: não foi possível publicar evento de liberação de estoque " + " para o pedido {}. Requer reconciliação manual.",
                    orderId, ex);
        }
    }

    private void confirmStock(List<StockItemRequestDTO> items, UUID orderId) {
        try {
            stockEventPublisher.publishConfirm(orderId, items);
            log.info("Evento de confirmação de estoque publicado para o pedido {}.", orderId);
        } catch (Exception ex) {
            log.error("FALHA GRAVE: não foi possível publicar evento de confirmação de estoque " +
                    "para o pedido {}. Requer reconciliação manual.", orderId, ex);
        }
    }
}
