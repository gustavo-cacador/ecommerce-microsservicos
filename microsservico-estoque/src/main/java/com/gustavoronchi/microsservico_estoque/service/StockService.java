package com.gustavoronchi.microsservico_estoque.service;

import com.gustavoronchi.microsservico_estoque.config.RabbitMQConfig;
import com.gustavoronchi.microsservico_estoque.domain.entities.OutboxEvent;
import com.gustavoronchi.microsservico_estoque.domain.entities.Product;
import com.gustavoronchi.microsservico_estoque.domain.entities.StockReservation;
import com.gustavoronchi.microsservico_estoque.domain.entities.StockReservationItem;
import com.gustavoronchi.microsservico_estoque.domain.repository.ProductRepository;
import com.gustavoronchi.microsservico_estoque.domain.repository.OutboxEventRepository;
import com.gustavoronchi.microsservico_estoque.domain.repository.StockReservationRepository;
import com.gustavoronchi.microsservico_estoque.dto.ReservedItemDTO;
import com.gustavoronchi.microsservico_estoque.dto.StockItemRequestDTO;
import com.gustavoronchi.microsservico_estoque.dto.StockItemResponseDTO;
import com.gustavoronchi.microsservico_estoque.enums.ReservationStatus;
import com.gustavoronchi.microsservico_estoque.exception.InvalidStockRequestException;
import com.gustavoronchi.microsservico_estoque.exception.ProductNotFoundException;
import com.gustavoronchi.microsservico_estoque.exception.StockInconsistencyException;
import com.gustavoronchi.microsservico_estoque.exception.StockReservationNotFoundException;
import com.gustavoronchi.microsservico_estoque.messaging.OrderCreatedEvent;
import com.gustavoronchi.microsservico_estoque.messaging.StockReservedEvent;
import com.gustavoronchi.microsservico_estoque.messaging.StockReservationFailedEvent;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

@Service
public class StockService {

    private final ProductRepository productRepository;
    private final StockReservationRepository reservationRepository;
    private final OutboxEventRepository outboxRepository;
    private final JsonMapper jsonMapper;
    private final TransactionTemplate transactionTemplate;

    public StockService(ProductRepository productRepository, StockReservationRepository reservationRepository,
                        OutboxEventRepository outboxRepository, JsonMapper jsonMapper, TransactionTemplate transactionTemplate) {
        this.productRepository = productRepository;
        this.reservationRepository = reservationRepository;
        this.outboxRepository = outboxRepository;
        this.jsonMapper = jsonMapper;
        this.transactionTemplate = transactionTemplate;
    }

    public void reserveOrder(OrderCreatedEvent message) {
        try {
            transactionTemplate.executeWithoutResult(status -> createReservationResult(message));
        } catch (DataIntegrityViolationException ex) {
            // A transação concorrente pode ter concluído este mesmo evento. Consulte só após o rollback.
            if (outboxRepository.findBySourceEventId(message.getEventId()).isEmpty()) {
                throw ex;
            }
        }
    }

    private void createReservationResult(OrderCreatedEvent message) {
        if (outboxRepository.findBySourceEventId(message.getEventId()).isPresent()) {
            return;
        }

        OutboxEvent event = new OutboxEvent();
        event.setSourceEventId(message.getEventId());
        event.setOrderId(message.getOrderId());
        event.setExchange(RabbitMQConfig.STOCK_RESERVED_EXCHANGE);
        event.setRoutingKey("");
        event.setOccurredAt(Instant.now().truncatedTo(ChronoUnit.MICROS));
        event.setPayload("");
        // O sourceEventId único serializa reentregas, inclusive sem produto ou reserva para bloquear.
        // Este registro só fica visível após o commit, já com o payload do resultado preenchido.
        event = outboxRepository.saveAndFlush(event);

        StockItemResponseDTO response = reserve(message.getOrderId(), message.getItems());
        if (response.isSuccess()) {
            StockReservation reservation = reservationRepository.findByOrderId(message.getOrderId()).orElseThrow();
            StockReservedEvent result = new StockReservedEvent(event.getEventId(), message.getOrderId(),
                    event.getOccurredAt(), reservation.getId(), message.getAmount(), message.getCurrency());
            event.setPayload(jsonMapper.writeValueAsString(result));
        } else {
            event.setExchange(RabbitMQConfig.STOCK_RESERVATION_FAILED_EXCHANGE);
            StockReservationFailedEvent result = new StockReservationFailedEvent(event.getEventId(), message.getOrderId(),
                    event.getOccurredAt(), response.getFailureReason());
            event.setPayload(jsonMapper.writeValueAsString(result));
        }
        outboxRepository.save(event);
    }

    @Transactional
    public StockItemResponseDTO reserve(UUID orderId, List<StockItemRequestDTO> items) {
        validateOrderId(orderId);
        Map<UUID, Integer> quantities = consolidateItems(items);

        StockReservation existing = reservationRepository.findByOrderId(orderId).orElse(null);
        if (existing != null) {
            return repeatedReservation(existing, quantities);
        }

        List<Product> blockedProducts = new ArrayList<>();
        for (UUID productId : quantities.keySet()) {
            Product product = productRepository.findByIdForUpdate(productId).orElse(null);
            if (product == null) {
                return new StockItemResponseDTO(false, "Produto não encontrado: " + productId);
            }
            blockedProducts.add(product);
        }

        // Outra tentativa do mesmo pedido pode ter reservado enquanto aguardávamos os locks.
        existing = reservationRepository.findByOrderId(orderId).orElse(null);
        if (existing != null) {
            return repeatedReservation(existing, quantities);
        }

        for (Product product : blockedProducts) {
            if (!Boolean.TRUE.equals(product.getActive())) {
                return new StockItemResponseDTO(false, "Produto inativo: " + product.getId());
            }
            int available = product.getQuantityAvailable() - product.getQuantityReserved();
            if (available < quantities.get(product.getId())) {
                return new StockItemResponseDTO(false, "Estoque insuficiente para o produto " + product.getName());
            }
        }

        StockReservation reservation = new StockReservation();
        reservation.setOrderId(orderId);
        reservation.setStatus(ReservationStatus.RESERVED);

        for (Product product : blockedProducts) {
            StockReservationItem item = new StockReservationItem();
            item.setReservation(reservation);
            item.setProductId(product.getId());
            item.setQuantity(quantities.get(product.getId()));
            item.setPrice(product.getPrice());
            reservation.getItems().add(item);
        }

        try {
            reservationRepository.saveAndFlush(reservation);
        } catch (DataIntegrityViolationException ex) {
            // Também protege tentativas simultâneas do mesmo pedido com produtos diferentes.
            throw new StockInconsistencyException("Já existe uma reserva para o pedido " + orderId);
        }

        for (Product product : blockedProducts) {
            product.setQuantityReserved(product.getQuantityReserved() + quantities.get(product.getId()));
        }
        return reservationResponse(reservation);
    }

    @Transactional
    public void release(UUID orderId) {
        finishReservation(orderId, null, ReservationStatus.RELEASED);
    }

    @Transactional
    public void release(UUID orderId, UUID reservationId) {
        if (reservationId == null) {
            throw new InvalidStockRequestException("Informe a reserva do pedido.");
        }
        finishReservation(orderId, reservationId, ReservationStatus.RELEASED);
    }

    @Transactional
    public void confirm(UUID orderId) {
        finishReservation(orderId, null, ReservationStatus.CONFIRMED);
    }

    @Transactional
    public void confirm(UUID orderId, UUID reservationId) {
        if (reservationId == null) {
            throw new InvalidStockRequestException("Informe a reserva do pedido.");
        }
        finishReservation(orderId, reservationId, ReservationStatus.CONFIRMED);
    }

    private void finishReservation(UUID orderId, UUID reservationId, ReservationStatus targetStatus) {
        validateOrderId(orderId);
        StockReservation reservation = reservationRepository.findByOrderIdForUpdate(orderId)
                .orElseThrow(() -> new StockReservationNotFoundException(orderId));

        if (reservationId != null && !reservation.getId().equals(reservationId)) {
            throw new StockInconsistencyException("Reserva informada não pertence ao pedido " + orderId);
        }
        if (reservation.getStatus() == targetStatus) {
            return;
        }
        if (reservation.getStatus() != ReservationStatus.RESERVED) {
            throw new StockInconsistencyException("Reserva do pedido " + orderId
                    + " já finalizada como " + reservation.getStatus());
        }

        List<StockReservationItem> items = reservation.getItems().stream()
                .sorted(Comparator.comparing(StockReservationItem::getProductId))
                .toList();

        for (StockReservationItem item : items) {
            Product product = productRepository.findByIdForUpdate(item.getProductId())
                    .orElseThrow(() -> new ProductNotFoundException(
                            "Produto com id: " + item.getProductId() + " não encontrado."));

            if (product.getQuantityReserved() < item.getQuantity()) {
                throw new StockInconsistencyException(
                        "Quantidade reservada insuficiente para o produto " + product.getId());
            }

            if (targetStatus == ReservationStatus.CONFIRMED) {
                if (product.getQuantityAvailable() < item.getQuantity()) {
                    throw new StockInconsistencyException(
                            "Quantidade disponível insuficiente para o produto " + product.getId());
                }
                product.setQuantityAvailable(product.getQuantityAvailable() - item.getQuantity());
            }

            product.setQuantityReserved(product.getQuantityReserved() - item.getQuantity());
        }
        reservation.setStatus(targetStatus);
    }

    private Map<UUID, Integer> consolidateItems(List<StockItemRequestDTO> items) {
        if (items == null || items.isEmpty()) {
            throw new InvalidStockRequestException("Informe ao menos um item para reservar.");
        }

        Map<UUID, Integer> quantities = new TreeMap<>();
        for (StockItemRequestDTO item : items) {
            if (item == null || item.getProductId() == null
                    || item.getQuantity() == null || item.getQuantity() <= 0) {
                throw new InvalidStockRequestException("Cada item deve ter produto e quantidade positiva.");
            }
            try {
                quantities.merge(item.getProductId(), item.getQuantity(), Math::addExact);
            } catch (ArithmeticException ex) {
                throw new InvalidStockRequestException("Quantidade total excede o limite para o produto "
                        + item.getProductId());
            }
        }
        return quantities;
    }

    private StockItemResponseDTO repeatedReservation(StockReservation reservation, Map<UUID, Integer> quantities) {
        Map<UUID, Integer> reservedQuantities = new TreeMap<>();
        for (StockReservationItem item : reservation.getItems()) {
            reservedQuantities.put(item.getProductId(), item.getQuantity());
        }
        if (!reservedQuantities.equals(quantities)) {
            throw new StockInconsistencyException("Pedido já possui reserva com itens diferentes.");
        }
        if (reservation.getStatus() == ReservationStatus.RELEASED) {
            throw new StockInconsistencyException("A reserva deste pedido já foi liberada.");
        }
        return reservationResponse(reservation);
    }

    private StockItemResponseDTO reservationResponse(StockReservation reservation) {
        List<ReservedItemDTO> items = reservation.getItems().stream()
                .map(item -> new ReservedItemDTO(item.getProductId(), item.getPrice()))
                .toList();
        return new StockItemResponseDTO(true, null, items);
    }

    private void validateOrderId(UUID orderId) {
        if (orderId == null) {
            throw new InvalidStockRequestException("Informe o pedido da reserva.");
        }
    }
}
