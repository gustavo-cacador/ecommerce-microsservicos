package com.gustavoronchi.microsservico_estoque.messaging;

import com.gustavoronchi.microsservico_estoque.dto.StockItemRequestDTO;

import java.util.List;
import java.util.UUID;

public class StockActionMessage {

    private UUID orderId;
    private List<StockItemRequestDTO> items;

    public StockActionMessage() {
    }

    public StockActionMessage(UUID orderId, List<StockItemRequestDTO> items) {
        this.orderId = orderId;
        this.items = items;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public void setOrderId(UUID orderId) {
        this.orderId = orderId;
    }

    public List<StockItemRequestDTO> getItems() {
        return items;
    }

    public void setItems(List<StockItemRequestDTO> items) {
        this.items = items;
    }
}
