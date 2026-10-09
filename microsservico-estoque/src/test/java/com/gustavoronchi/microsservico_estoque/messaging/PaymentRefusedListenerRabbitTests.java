package com.gustavoronchi.microsservico_estoque.messaging;

import com.gustavoronchi.microsservico_estoque.config.RabbitMQConfig;
import com.gustavoronchi.microsservico_estoque.domain.entities.Product;
import com.gustavoronchi.microsservico_estoque.domain.repository.ProductRepository;
import com.gustavoronchi.microsservico_estoque.domain.repository.StockReservationRepository;
import com.gustavoronchi.microsservico_estoque.dto.StockItemRequestDTO;
import com.gustavoronchi.microsservico_estoque.enums.ReservationStatus;
import com.gustavoronchi.microsservico_estoque.service.StockService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

// Executar somente com um broker isolado: as filas desse teste são declaradas e limpas nesse broker.
@EnabledIfEnvironmentVariable(named = "RABBITMQ_TEST_PORT", matches = "\\d+")
@SpringBootTest(properties = {
        "spring.rabbitmq.port=${RABBITMQ_TEST_PORT}",
        "spring.datasource.url=jdbc:h2:mem:estoque-refusal-rabbit-test",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never"
})
class PaymentRefusedListenerRabbitTests {

    @Autowired
    private RabbitTemplate rabbit;
    @Autowired
    private StockService service;
    @Autowired
    private ProductRepository products;
    @Autowired
    private StockReservationRepository reservations;
    @MockitoBean
    private OutboxRelay outboxRelay;

    @BeforeEach
    void setUp() {
        reservations.deleteAll();
        products.deleteAll();
        rabbit.execute(channel -> {
            channel.queuePurge(RabbitMQConfig.STOCK_PAYMENT_REFUSED_QUEUE);
            channel.queuePurge(RabbitMQConfig.STOCK_PAYMENT_REFUSED_DLQ);
            return null;
        });
    }

    @Test
    void refusalAndRedeliveryReleaseOnlyOnceAndPreserveAnotherOrdersReservation() throws Exception {
        Product product = product();
        UUID orderId = reserve(product);
        UUID otherOrderId = reserve(product);
        UUID reservationId = reservations.findByOrderId(orderId).orElseThrow().getId();
        String payload = refusal(orderId, reservationId);

        publish(payload);
        await(() -> reservations.findByOrderId(orderId).orElseThrow().getStatus() == ReservationStatus.RELEASED);
        publish(payload);
        publish(payload);
        publish("{}");
        await(() -> messages(RabbitMQConfig.STOCK_PAYMENT_REFUSED_DLQ) == 1);

        assertStock(product, 5, 2);
        assertThat(reservations.findByOrderId(orderId).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.RELEASED);
        assertThat(reservations.findByOrderId(otherOrderId).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.RESERVED);
    }

    @Test
    void invalidReservationUnknownOrderAndConfirmedReservationGoToDeadLetter() throws Exception {
        Product product = product();
        UUID orderId = reserve(product);
        UUID reservationId = reservations.findByOrderId(orderId).orElseThrow().getId();
        publish(refusal(orderId, UUID.randomUUID()));
        publish(refusal(UUID.randomUUID(), reservationId));
        publish("{invalid-json");
        publish("{}");
        await(() -> messages(RabbitMQConfig.STOCK_PAYMENT_REFUSED_DLQ) == 4);
        assertStock(product, 5, 2);
        assertThat(reservations.findByOrderId(orderId).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.RESERVED);

        service.confirm(orderId, reservationId);
        publish(refusal(orderId, reservationId));
        await(() -> messages(RabbitMQConfig.STOCK_PAYMENT_REFUSED_DLQ) == 5);
        assertStock(product, 3, 0);
        assertThat(reservations.findByOrderId(orderId).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.CONFIRMED);
    }

    private Product product() {
        return products.saveAndFlush(new Product(null, "Produto de teste", null,
                BigDecimal.TEN, null, 5, 0, UUID.randomUUID(), true));
    }

    private UUID reserve(Product product) {
        UUID orderId = UUID.randomUUID();
        service.reserve(orderId, List.of(new StockItemRequestDTO(product.getId(), 2)));
        return orderId;
    }

    private String refusal(UUID orderId, UUID reservationId) {
        return """
                {"eventId":"%s","orderId":"%s","occurredAt":"2026-10-09T12:00:00Z",
                 "paymentId":"%s","reservationId":"%s","amount":20.00,"currency":"BRL"}
                """.formatted(UUID.randomUUID(), orderId, UUID.randomUUID(), reservationId);
    }

    private void publish(String payload) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        rabbit.send(RabbitMQConfig.PAYMENT_REFUSED_EXCHANGE, "",
                new Message(payload.getBytes(StandardCharsets.UTF_8), properties));
    }

    private long messages(String queue) {
        Long count = rabbit.execute(channel -> channel.messageCount(queue));
        return count == null ? 0 : count;
    }

    private void assertStock(Product product, int available, int reserved) {
        Product persisted = products.findById(product.getId()).orElseThrow();
        assertThat(persisted.getQuantityAvailable()).isEqualTo(available);
        assertThat(persisted.getQuantityReserved()).isEqualTo(reserved);
    }

    private void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }
}
