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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    public OrderService(OrderRepository orderRepository, StockClient stockClient, PaymentClient paymentClient) {
        this.orderRepository = orderRepository;
        this.stockClient = stockClient;
        this.paymentClient = paymentClient;
    }

    @Transactional(readOnly = true)
    public OrderResponseDTO findById(UUID id) {
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new OrderNotFoundException("Pedido com id: " + id + " não encontrado."));
        return new OrderResponseDTO(order);
    }

    public OrderResponseDTO createOrder(OrderRequestDTO orderRequestDTO) {
        // mapeia os itens solicitados para o dto de reserva do estoque
        List<StockItemRequestDTO> itemToReserve = orderRequestDTO.getItems()
                .stream()
                .map(item -> new StockItemRequestDTO(item.getProductId(), item.getQuantity()))
                .toList();

        StockReserveResponseDTO response;

        // tenta contatar o serviço de estoque via http
        try {
            response = stockClient.reserve(itemToReserve);
        } catch (RestClientException ex) {
            throw new StockUnavailableException("Não foi possível contatar o serviço de estoque: " + ex.getMessage());
        }

        // valida se a reserva foi autorizada pelo estoque
        if (!response.isSuccess()) {
            throw new StockUnavailableException(response.getFailureReason());
        }

        Order order;

        // inicio da transação do pedido (com tratamento saga)
        try {
            Map<UUID, BigDecimal> pricesByProduct = response
                    .getItems()
                    .stream()
                    .collect(Collectors.toMap(ReservedItemDTO::getProductId, ReservedItemDTO::getPrice));

            order = new Order();
            order.setClientId(orderRequestDTO.getClientId());
            order.setStatus(StatusOrder.WAITING_PAYMENT);
            order.setCreatedAt(Instant.now());
            order.setUpdatedAt(Instant.now());

            for (OrderItemRequestDTO itemRequestDTO : orderRequestDTO.getItems()) {
                OrderItem orderItem = new OrderItem();
                orderItem.setOrder(order);
                orderItem.setProductId(itemRequestDTO.getProductId());
                orderItem.setQuantity(itemRequestDTO.getQuantity());
                orderItem.setPrice(pricesByProduct.get(itemRequestDTO.getProductId()));
                order.getItems().add(orderItem);
            }

            BigDecimal total = order.getItems()
                    .stream()
                    .map(item -> item.getPrice()
                            .multiply(BigDecimal.valueOf(item.getQuantity())))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            order.setTotalValue(total);

            order = orderRepository.save(order);

        } catch (Exception ex) {
            log.error("Erro interno ao criar pedido. Tentando acionar rollback de estoque...", ex);

            try {
                stockClient.release(itemToReserve);
                log.info("Rollback de estoque concluído com sucesso.");
            } catch (Exception releaseEx) {
                log.error("FALHA GRAVE: Não foi possível realizar o rollback do estoque! " +
                        "Os itens podem ter ficado presos. Erro do release: {}", releaseEx.getMessage());
            }
            throw new RuntimeException("Erro ao processar pedido. A transação foi revertida.", ex);
        }

        PaymentResponseDTO paymentResponse;
        try {
            paymentResponse = paymentClient.process(new PaymentRequestDTO(order.getId(), order.getTotalValue()));
        } catch (RestClientException ex) {
            log.error("Erro ao contatar serviço de pagamento. Revertendo estoque e marcando pedido como recusado.", ex);

            try {
                stockClient.release(itemToReserve);
                log.info("Rollback de estoque concluído com sucesso.");
            } catch (Exception releaseEx) {
                log.error("FALHA GRAVE: Não foi possível realizar o rollback do estoque! " +
                        "Os itens podem ter ficado presos. Erro do release: {}", releaseEx.getMessage());
            }

            order.setStatus(StatusOrder.DECLINED_PAYMENT);
            order.setUpdatedAt(Instant.now());
            orderRepository.save(order);

            throw new PaymentUnavailableException("Não foi possível contatar o serviço de pagamento: " + ex.getMessage());
        }

        if ("APPROVED".equals(paymentResponse.getStatus())) {
            order.setStatus(StatusOrder.PAID);
            stockClient.confirm(itemToReserve);
        } else {
            order.setStatus(StatusOrder.DECLINED_PAYMENT);
            stockClient.release(itemToReserve);
        }

        order.setUpdatedAt(Instant.now());
        Order createdOrder = orderRepository.save(order);

        return new OrderResponseDTO(createdOrder);
    }
}
