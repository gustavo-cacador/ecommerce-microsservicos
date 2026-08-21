package com.gustavoronchi.microsservico_pedido.enums;

public enum StatusOrder {
    CREATED,
    WAITING_PAYMENT,
    APPROVED_PAYMENT,
    DECLINED_PAYMENT,
    PAID,
    SHIPPED,
    DELIVERED,
    CANCELED
}
