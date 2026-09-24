package com.gustavoronchi.microsservico_estoque.exception;

public class StockInconsistencyException extends RuntimeException {
    public StockInconsistencyException(String message) {
        super(message);
    }
}
