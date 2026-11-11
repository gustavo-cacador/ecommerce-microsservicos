package com.gustavoronchi.microsservico_pedido.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

public class OrderRequestDTO {

    @NotNull
    private UUID clientId;
    @NotEmpty
    private List<@NotNull @Valid OrderItemRequestDTO> items;

    public OrderRequestDTO() {
    }

    public OrderRequestDTO(UUID clientId, List<OrderItemRequestDTO> items) {
        this.clientId = clientId;
        this.items = items;
    }

    public UUID getClientId() {
        return clientId;
    }

    public void setClientId(UUID clientId) {
        this.clientId = clientId;
    }

    public List<OrderItemRequestDTO> getItems() {
        return items;
    }

    public void setItems(List<OrderItemRequestDTO> items) {
        this.items = items;
    }
}
