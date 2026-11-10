package com.gustavoronchi.e2e;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class CheckoutPaymentTests {

    // Produtos distintos do data.sql do estoque, em um banco novo a cada execução.
    private static final String KEYBOARD = "11111111-1111-1111-1111-111111111111";
    private static final String MOUSE = "22222222-2222-2222-2222-222222222222";

    private static final CheckoutEnvironment checkout = new CheckoutEnvironment();
    private static int keyboardInitial;
    private static int mouseInitial;

    @BeforeAll
    static void startServices() throws Exception {
        checkout.start();
        keyboardInitial = initialStock(KEYBOARD);
        mouseInitial = initialStock(MOUSE);
    }

    @AfterAll
    static void stopServices() throws Exception {
        checkout.close();
    }

    @Test
    void approvesPaymentAndDeductsPurchasedQuantity() throws Exception {
        int purchased = 2;
        UUID orderId = createOrder(KEYBOARD, purchased, "499.80");

        awaitCheckout(orderId, KEYBOARD, new CheckoutState("APPROVED", "PAID", "CONFIRMED",
                keyboardInitial - purchased, 0));
    }

    @Test
    void refusesPaymentAndRestoresAvailableQuantity() throws Exception {
        UUID orderId = createOrder(MOUSE, 4, "599.60");

        awaitCheckout(orderId, MOUSE, new CheckoutState("REFUSED", "CANCELED", "RELEASED", mouseInitial, 0));
    }

    private static int initialStock(String productId) throws Exception {
        var product = checkout.get(checkout.stock().resolve("/products/" + productId));
        assertThat(product.required("quantityReserved").intValue()).isZero();
        return product.required("quantityAvailable").intValue();
    }

    private UUID createOrder(String productId, int quantity, String amount) throws Exception {
        var order = checkout.send(HttpRequest.newBuilder(checkout.orders().resolve("/orders"))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"clientId":"%s","items":[{"productId":"%s","quantity":%d}]}
                        """.formatted(UUID.randomUUID(), productId, quantity))), 201);
        assertThat(order.required("totalValue").decimalValue()).isEqualByComparingTo(amount);
        return UUID.fromString(order.required("orderId").stringValue());
    }

    private void awaitCheckout(UUID orderId, String productId, CheckoutState expected) {
        await("checkout do pedido " + orderId + " / produto " + productId)
                .pollInterval(Duration.ofMillis(250)).atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> {
                    String payment = checkout.queryStatus("pagamento",
                            "SELECT status FROM payments WHERE order_id = ?", orderId);
                    String reservation = checkout.queryStatus("estoque",
                            "SELECT status FROM stock_reservations WHERE order_id = ?", orderId);
                    var order = checkout.get(checkout.orders().resolve("/orders/" + orderId));
                    var product = checkout.get(checkout.stock().resolve("/products/" + productId));

                    assertThat(new CheckoutState(payment, order.required("status").stringValue(), reservation,
                            product.required("quantityAvailable").intValue(), product.required("quantityReserved").intValue()))
                            .isEqualTo(expected);
                });
    }

    private record CheckoutState(String payment, String order, String reservation, int available, int reserved) {
    }
}
