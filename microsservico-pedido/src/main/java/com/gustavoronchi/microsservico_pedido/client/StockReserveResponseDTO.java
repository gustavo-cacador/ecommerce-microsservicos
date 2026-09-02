package com.gustavoronchi.microsservico_pedido.client;

public class StockReserveResponseDTO {

    private boolean success;
    private String failureReason;

    public StockReserveResponseDTO() {
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }
}
