package com.gustavoronchi.microsservico_estoque.dto;

import java.util.ArrayList;
import java.util.List;

public class StockItemResponseDTO {

    private boolean success;
    private String failureReason;
    private List<ReservedItemDTO> items = new ArrayList<>();

    public StockItemResponseDTO() {
    }

    public StockItemResponseDTO(boolean success, String failureReason) {
        this.success = success;
        this.failureReason = failureReason;
    }

    public StockItemResponseDTO(boolean success, String failureReason, List<ReservedItemDTO> items) {
        this.success = success;
        this.failureReason = failureReason;
        this.items = items;
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
