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
        "spring.datasource.url=jdbc:h2:mem:estoque-approval-rabbit-test",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never"
})
class PaymentApprovedListenerRabbitTests {

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
            channel.queuePurge(RabbitMQConfig.STOCK_PAYMENT_APPROVED_QUEUE);
            channel.queuePurge(RabbitMQConfig.STOCK_PAYMENT_APPROVED_DLQ);
            return null;
        });
    }

    @Test
    void approvalAndRedeliveryConfirmStockOnlyOnce() throws Exception {
        Product product = product();
        UUID orderId = reserve(product);
        UUID reservationId = reservations.findByOrderId(orderId).orElseThrow().getId();
        String payload = approval(orderId, reservationId);

        publish(payload);
        await(() -> reservations.findByOrderId(orderId).orElseThrow().getStatus() == ReservationStatus.CONFIRMED);
        publish(payload);
        publish(payload);
        publish("{}");
        await(() -> messages(RabbitMQConfig.STOCK_PAYMENT_APPROVED_DLQ) == 1);

        assertStock(product, 3, 0);
        assertThat(reservations.findByOrderId(orderId).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.CONFIRMED);
    }

    @Test
    void invalidReservationUnknownOrderAndReleasedReservationGoToDeadLetter() throws Exception {
        Product product = product();
        UUID orderId = reserve(product);
        UUID reservationId = reservations.findByOrderId(orderId).orElseThrow().getId();
        publish(approval(orderId, UUID.randomUUID()));
        publish(approval(UUID.randomUUID(), reservationId));
        publish("{invalid-json");
        await(() -> messages(RabbitMQConfig.STOCK_PAYMENT_APPROVED_DLQ) == 3);
        assertStock(product, 5, 2);
        assertThat(reservations.findByOrderId(orderId).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.RESERVED);

        service.release(orderId);
        publish(approval(orderId, reservationId));
        await(() -> messages(RabbitMQConfig.STOCK_PAYMENT_APPROVED_DLQ) == 4);
        assertStock(product, 5, 0);
        assertThat(reservations.findByOrderId(orderId).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.RELEASED);
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

    private String approval(UUID orderId, UUID reservationId) {
        return """
                {"eventId":"%s","orderId":"%s","occurredAt":"2026-10-06T12:00:00Z",
                 "paymentId":"%s","reservationId":"%s","amount":20.00,"currency":"BRL"}
                """.formatted(UUID.randomUUID(), orderId, UUID.randomUUID(), reservationId);
    }

    private void publish(String payload) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        rabbit.send(RabbitMQConfig.PAYMENT_APPROVED_EXCHANGE, "",
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
