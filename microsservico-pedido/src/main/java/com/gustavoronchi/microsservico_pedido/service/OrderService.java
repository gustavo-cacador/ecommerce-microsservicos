package com.gustavoronchi.microsservico_pedido.service;

import com.gustavoronchi.microsservico_pedido.client.ProductResponseDTO;
import com.gustavoronchi.microsservico_pedido.client.StockClient;
import com.gustavoronchi.microsservico_pedido.client.StockItemRequestDTO;
import com.gustavoronchi.microsservico_pedido.client.StockReserveResponseDTO;
import com.gustavoronchi.microsservico_pedido.domain.entities.Order;
import com.gustavoronchi.microsservico_pedido.domain.entities.OrderItem;
import com.gustavoronchi.microsservico_pedido.domain.repository.OrderRepository;
import com.gustavoronchi.microsservico_pedido.dto.OrderItemRequestDTO;
import com.gustavoronchi.microsservico_pedido.dto.OrderRequestDTO;
import com.gustavoronchi.microsservico_pedido.dto.OrderResponseDTO;
import com.gustavoronchi.microsservico_pedido.enums.StatusOrder;
import com.gustavoronchi.microsservico_pedido.exception.OrderNotFoundException;
import com.gustavoronchi.microsservico_pedido.exception.StockUnavailableException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final StockClient stockClient;

    public OrderService(OrderRepository orderRepository, StockClient stockClient) {
        this.orderRepository = orderRepository;
        this.stockClient = stockClient;
    }

    @Transactional(readOnly = true)
    public OrderResponseDTO findById(UUID id) {
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new OrderNotFoundException("Pedido com id: " + id + " não encontrado."));
        return new OrderResponseDTO(order);
    }

    @Transactional
    public OrderResponseDTO createOrder(OrderRequestDTO orderRequestDTO) {
        List<StockItemRequestDTO> itensParaReservar = orderRequestDTO.getItems().stream()
                .map(item -> new StockItemRequestDTO(item.getProductId(), item.getQuantity()))
                .toList();

        StockReserveResponseDTO resposta;
        try {
            resposta = stockClient.reserve(itensParaReservar);
        } catch (RestClientException ex) {
            throw new StockUnavailableException("Não foi possível contatar o serviço de estoque: " + ex.getMessage());
        }

        if (!resposta.isSuccess()) {
            throw new StockUnavailableException(resposta.getFailureReason());
        }

        Order order = new Order();
        order.setClientId(orderRequestDTO.getClientId());
        order.setStatus(StatusOrder.WAITING_PAYMENT);
        order.setCreatedAt(Instant.now());
        order.setUpdatedAt(Instant.now());

        for (OrderItemRequestDTO itemRequestDTO : orderRequestDTO.getItems()) {
            OrderItem orderItem = new OrderItem();
            orderItem.setOrder(order);
            orderItem.setProductId(itemRequestDTO.getProductId());
            orderItem.setQuantity(itemRequestDTO.getQuantity());

            ProductResponseDTO produto = stockClient.searchProduct(itemRequestDTO.getProductId());
            orderItem.setPrice(produto.getPrice());

            order.getItems().add(orderItem);
        }

        BigDecimal total = order.getItems().stream()
                .map(item -> item.getPrice().multiply(BigDecimal.valueOf(item.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        order.setTotalValue(total);

        Order createdOrder = orderRepository.save(order);
        return new OrderResponseDTO(createdOrder);
    }
}
