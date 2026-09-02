package com.gustavoronchi.microsservico_pedido.exception;

public class StockUnavailableException extends RuntimeException {
    public StockUnavailableException(String message) {
        super(message);
    }
}
