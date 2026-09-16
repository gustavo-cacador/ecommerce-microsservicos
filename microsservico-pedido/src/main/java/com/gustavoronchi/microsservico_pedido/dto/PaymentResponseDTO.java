package com.gustavoronchi.microsservico_pedido.dto;

public class PaymentResponseDTO {

    private String status;

    public PaymentResponseDTO() {
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
