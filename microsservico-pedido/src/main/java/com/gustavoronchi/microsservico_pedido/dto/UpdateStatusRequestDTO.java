package com.gustavoronchi.microsservico_pedido.dto;

import com.gustavoronchi.microsservico_pedido.enums.StatusOrder;

public class UpdateStatusRequestDTO {

    private StatusOrder status;

    public UpdateStatusRequestDTO() {
    }

    public UpdateStatusRequestDTO(StatusOrder status) {
        this.status = status;
    }

    public StatusOrder getStatus() {
        return status;
    }

    public void setStatus(StatusOrder status) {
        this.status = status;
    }
}
