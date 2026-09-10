package com.gustavoronchi.microsservico_pedido.client;

import java.util.ArrayList;
import java.util.List;

public class StockReserveResponseDTO {

    private boolean success;
    private String failureReason;
    private List<ReservedItemDTO> items = new ArrayList<>();

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

    public List<ReservedItemDTO> getItems() {
        return items;
    }

    public void setItems(List<ReservedItemDTO> items) {
        this.items = items;
    }
}
