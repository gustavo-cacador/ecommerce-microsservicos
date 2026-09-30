package com.gustavoronchi.microsservico_estoque.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.UUID;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class StockReservationNotFoundException extends RuntimeException {
    public StockReservationNotFoundException(UUID orderId) {
        super("Reserva não encontrada para o pedido " + orderId);
    }
}
