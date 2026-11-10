package com.gustavoronchi.e2e;

import org.awaitility.core.ConditionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static java.net.http.HttpRequest.BodyPublishers.ofString;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class CheckoutReservationDeliveryTests {

    // Cada cenário usa produtos diferentes do data.sql, em um ambiente exclusivo desta classe.
    private static final String KEYBOARD = "11111111-1111-1111-1111-111111111111";
    private static final String MOUSE = "22222222-2222-2222-2222-222222222222";
    private static final String HEADSET = "33333333-3333-3333-3333-333333333333";
    private static final String WEBCAM = "55555555-5555-5555-5555-555555555555";

    private static final String ORDER_CREATED = "order.created";
    private static final String STOCK_RESERVED = "stock.reserved";
    private static final String STOCK_CREATED_QUEUE = "stock.order.created.queue";
    private static final String PAYMENT_RESERVED_QUEUE = "payment.stock.reserved.queue";
    private static final String ORDER_RESERVED_QUEUE = "order.stock.reserved.queue";
    private static final String QUEUES = "queues/%2F/";

    private static final CheckoutEnvironment checkout = new CheckoutEnvironment();
    private final JsonMapper json = new JsonMapper();

    @BeforeAll
    static void startServices() throws Exception {
        checkout.start();
    }

    @AfterAll
    static void stopServices() throws Exception {
        checkout.close();
    }

    @Test
    void cancelsWhenStockRunsOutAfterOrderIsAccepted() throws Exception {
        int keyboardStock = availableStock(KEYBOARD);
        assertThat(availableStock(MOUSE)).isPositive();
        long stockAcks = acks(STOCK_CREATED_QUEUE);
        String captured = captureOn(ORDER_CREATED);
        unbind(ORDER_CREATED, STOCK_CREATED_QUEUE);

        UUID orderId;
        try {
            orderId = createOrder(new Item(KEYBOARD, 1), new Item(MOUSE, 1));
            JsonNode created = take(captured, orderId);
            assertThat(order(orderId).required("status").stringValue()).isEqualTo("CREATED");

            URI mouse = productUri(MOUSE);
            ObjectNode product = (ObjectNode) checkout.get(mouse);
            product.remove(List.of("id", "active", "quantityReserved"));
            product.put("quantityAvailable", 0);
            checkout.send(HttpRequest.newBuilder(mouse).header("Content-Type", "application/json")
                    .PUT(ofString(json.writeValueAsString(product))), 200);

            publish("amq.default", STOCK_CREATED_QUEUE, created);
            awaitAcks(Map.of(STOCK_CREATED_QUEUE, stockAcks + 1));
            poll("cancelamento do pedido " + orderId).untilAsserted(() ->
                    assertThat(order(orderId).required("status").stringValue()).isEqualTo("CANCELED"));
        } finally {
            bind(ORDER_CREATED, STOCK_CREATED_QUEUE);
        }

        assertThat(checkout.queryColumn("estoque", "SELECT exchange_name FROM outbox_events WHERE order_id = ?", orderId))
                .containsExactly("stock.reservation.failed");
        assertThat(checkout.queryColumn("estoque", "SELECT status FROM stock_reservations WHERE order_id = ?", orderId))
                .isEmpty();
        assertThat(checkout.queryColumn("estoque", "SELECT id FROM stock_reservation_items WHERE product_id IN (?, ?)",
                UUID.fromString(KEYBOARD), UUID.fromString(MOUSE))).isEmpty();
        assertThat(checkout.queryColumn("pagamento", "SELECT status FROM payments WHERE order_id = ?", orderId)).isEmpty();
        assertStock(KEYBOARD, keyboardStock);
        assertStock(MOUSE, 0);
    }

    @Test
    void replayDoesNotRepeatReservationPaymentOrStockDeduction() throws Exception {
        int initialStock = availableStock(HEADSET);
        long stockAcks = acks(STOCK_CREATED_QUEUE);
        long paymentAcks = acks(PAYMENT_RESERVED_QUEUE);
        long orderAcks = acks(ORDER_RESERVED_QUEUE);
        String createdQueue = captureOn(ORDER_CREATED);
        String reservedQueue = captureOn(STOCK_RESERVED);

        UUID orderId = createOrder(new Item(HEADSET, 2));
        JsonNode created = take(createdQueue, orderId);
        JsonNode reserved = take(reservedQueue, orderId);
        awaitPaid(orderId, HEADSET, initialStock, 2);

        publish(ORDER_CREATED, "", created);
        publish(STOCK_RESERVED, "", reserved);

        awaitAcks(Map.of(STOCK_CREATED_QUEUE, stockAcks + 2,
                PAYMENT_RESERVED_QUEUE, paymentAcks + 2, ORDER_RESERVED_QUEUE, orderAcks + 2));
        assertPaid(orderId, HEADSET, initialStock, 2);
        assertThat(checkout.queryColumn("estoque", "SELECT exchange_name FROM outbox_events WHERE order_id = ?", orderId))
                .containsExactly(STOCK_RESERVED);
        assertThat(checkout.queryColumn("pagamento", "SELECT exchange_name FROM outbox_events WHERE order_id = ?", orderId))
                .containsExactly("payment.approved");
    }

    @Test
    void lateStockReservedDoesNotReopenPaidOrder() throws Exception {
        int initialStock = availableStock(WEBCAM);
        long stockAcks = acks(STOCK_CREATED_QUEUE);
        long paymentAcks = acks(PAYMENT_RESERVED_QUEUE);
        long orderAcks = acks(ORDER_RESERVED_QUEUE);
        String captured = captureOn(STOCK_RESERVED);
        unbind(STOCK_RESERVED, ORDER_RESERVED_QUEUE);

        try {
            UUID orderId = createOrder(new Item(WEBCAM, 1));
            JsonNode reserved = take(captured, orderId);
            awaitPaid(orderId, WEBCAM, initialStock, 1);
            assertThat(acks(ORDER_RESERVED_QUEUE)).isEqualTo(orderAcks);

            publish("amq.default", ORDER_RESERVED_QUEUE, reserved);

            awaitAcks(Map.of(STOCK_CREATED_QUEUE, stockAcks + 1,
                    PAYMENT_RESERVED_QUEUE, paymentAcks + 1, ORDER_RESERVED_QUEUE, orderAcks + 1));
            assertPaid(orderId, WEBCAM, initialStock, 1);
        } finally {
            bind(STOCK_RESERVED, ORDER_RESERVED_QUEUE);
        }
    }

    private UUID createOrder(Item... items) throws Exception {
        String body = json.writeValueAsString(Map.of("clientId", UUID.randomUUID(), "items", items));
        JsonNode order = checkout.send(HttpRequest.newBuilder(checkout.orders().resolve("/orders"))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", UUID.randomUUID().toString()).POST(ofString(body)), 201);
        return UUID.fromString(order.required("orderId").stringValue());
    }

    private JsonNode order(UUID orderId) throws Exception {
        return checkout.get(checkout.orders().resolve("/orders/" + orderId));
    }

    private void awaitPaid(UUID orderId, String productId, int initialStock, int quantity) {
        poll("pagamento e reserva finalizados para " + orderId)
                .untilAsserted(() -> assertPaid(orderId, productId, initialStock, quantity));
    }

    private void assertPaid(UUID orderId, String productId, int initialStock, int quantity) throws Exception {
        assertThat(order(orderId).required("status").stringValue()).isEqualTo("PAID");
        assertThat(checkout.queryColumn("pagamento", "SELECT status FROM payments WHERE order_id = ?", orderId))
                .containsExactly("APPROVED");
        assertThat(checkout.queryColumn("estoque", "SELECT status FROM stock_reservations WHERE order_id = ?", orderId))
                .containsExactly("CONFIRMED");
        assertThat(checkout.queryColumn("estoque", """
                SELECT i.quantity FROM stock_reservation_items i
                JOIN stock_reservations r ON r.id = i.reservation_id WHERE r.order_id = ?
                """, orderId)).containsExactly(Integer.toString(quantity));
        assertStock(productId, initialStock - quantity);
    }

    private URI productUri(String productId) {
        return checkout.stock().resolve("/products/" + productId);
    }

    private int availableStock(String productId) throws Exception {
        return checkout.get(productUri(productId)).required("quantityAvailable").intValue();
    }

    private void assertStock(String productId, int available) throws Exception {
        JsonNode product = checkout.get(productUri(productId));
        assertThat(product.required("quantityAvailable").intValue()).as("disponível de %s", productId).isEqualTo(available);
        assertThat(product.required("quantityReserved").intValue()).as("reservado de %s", productId).isZero();
    }

    private String captureOn(String exchange) throws Exception {
        String queue = "checkout-e2e-" + UUID.randomUUID();
        checkout.send(checkout.rabbitRequest(QUEUES + queue).PUT(ofString("{}")), 201);
        bind(exchange, queue);
        return queue;
    }

    private void bind(String exchange, String queue) throws Exception {
        checkout.send(checkout.rabbitRequest(binding(exchange, queue)).POST(ofString("{}")), 201);
    }

    private void unbind(String exchange, String queue) throws Exception {
        // A topologia usa fanout com chave de roteamento vazia, representada por ~ na API do RabbitMQ.
        checkout.send(checkout.rabbitRequest(binding(exchange, queue) + "/~").DELETE(), 204);
    }

    private String binding(String exchange, String queue) {
        return "bindings/%2F/e/" + exchange + "/q/" + queue;
    }

    private JsonNode take(String queue, UUID orderId) throws Exception {
        JsonNode messages = poll("evento de " + orderId + " em " + queue).until(() -> checkout.send(
                checkout.rabbitRequest(QUEUES + queue + "/get").POST(ofString("""
                        {"count":1,"ackmode":"ack_requeue_false","encoding":"auto"}
                        """)), 200), result -> result.size() == 1);
        checkout.send(checkout.rabbitRequest(QUEUES + queue).DELETE(), 204);
        JsonNode message = messages.required(0);
        JsonNode payload = json.readTree(message.required("payload").stringValue());
        assertThat(payload.required("orderId").stringValue()).isEqualTo(orderId.toString());
        assertThat(message.required("properties").required("message_id").stringValue())
                .isEqualTo(payload.required("eventId").stringValue());
        return message;
    }

    private void publish(String exchange, String routingKey, JsonNode message) throws Exception {
        String body = json.writeValueAsString(Map.of("routing_key", routingKey,
                "properties", message.required("properties"), "payload", message.required("payload"),
                "payload_encoding", "string"));
        JsonNode response = checkout.send(checkout.rabbitRequest("exchanges/%2F/" + exchange + "/publish")
                .POST(ofString(body)), 200);
        assertThat(response.required("routed").booleanValue()).as("roteamento para %s/%s", exchange, routingKey).isTrue();
    }

    private long acks(String queue) throws Exception {
        return checkout.send(checkout.rabbitRequest(QUEUES + queue).GET(), 200)
                .path("message_stats").path("ack").asLong();
    }

    private void awaitAcks(Map<String, Long> expected) {
        poll("confirmações dos consumidores " + expected).untilAsserted(() -> {
            for (var entry : expected.entrySet()) {
                assertThat(acks(entry.getKey())).as("acks em %s", entry.getKey()).isEqualTo(entry.getValue());
            }
        });
    }

    private ConditionFactory poll(String description) {
        return await(description).pollInterval(Duration.ofMillis(250)).atMost(Duration.ofSeconds(30));
    }

    private record Item(String productId, int quantity) {
    }
}
