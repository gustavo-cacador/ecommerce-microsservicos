package com.gustavoronchi.microsservico_estoque.messaging;

import com.gustavoronchi.microsservico_estoque.config.RabbitMQConfig;
import com.gustavoronchi.microsservico_estoque.domain.entities.Product;
import com.gustavoronchi.microsservico_estoque.domain.repository.OutboxEventRepository;
import com.gustavoronchi.microsservico_estoque.domain.repository.ProductRepository;
import com.gustavoronchi.microsservico_estoque.domain.repository.StockReservationRepository;
import com.gustavoronchi.microsservico_estoque.dto.StockItemRequestDTO;
import com.gustavoronchi.microsservico_estoque.enums.ReservationStatus;
import com.gustavoronchi.microsservico_estoque.service.StockService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.stubbing.Answer;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.CannotCreateTransactionException;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

// Executar somente com um broker isolado: este teste limpa suas filas nesse broker.
@EnabledIfEnvironmentVariable(named = "RABBITMQ_TEST_PORT", matches = "\\d+")
@SpringBootTest(properties = {
        "spring.rabbitmq.port=${RABBITMQ_TEST_PORT}",
        "spring.datasource.url=jdbc:h2:mem:estoque-retry-test;LOCK_TIMEOUT=200",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never"
})
class StockRetryRabbitTests {

    @Autowired
    private RabbitTemplate rabbit;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private StockService service;
    @Autowired
    private ProductRepository products;
    @Autowired
    private StockReservationRepository reservations;
    @Autowired
    private OutboxEventRepository events;
    @Autowired
    private JsonMapper jsonMapper;
    @MockitoBean
    private OutboxRelay outboxRelay;
    @MockitoSpyBean
    private OrderCreatedListener orderListener;
    @MockitoSpyBean
    private PaymentApprovedListener approvedListener;
    @MockitoSpyBean
    private PaymentRefusedListener refusedListener;

    private final List<Long> attempts = new CopyOnWriteArrayList<>();
    private volatile RuntimeException temporaryFailure;

    @BeforeEach
    void setUp() {
        events.deleteAll();
        reservations.deleteAll();
        products.deleteAll();
        attempts.clear();
        temporaryFailure = null;
        Answer<Object> recordAttempt = invocation -> {
            attempts.add(System.nanoTime());
            if (temporaryFailure != null && attempts.size() < 3) {
                throw temporaryFailure;
            }
            return invocation.callRealMethod();
        };
        doAnswer(recordAttempt).when(orderListener).hearOrderCreated(any());
        doAnswer(recordAttempt).when(approvedListener).hearPaymentApproved(any());
        doAnswer(recordAttempt).when(refusedListener).hearPaymentRefused(any());
        rabbit.execute(channel -> {
            for (String operation : List.of("reserve", "confirm", "release")) {
                channel.queuePurge(queue(operation));
                channel.queuePurge(dlq(operation));
            }
            return null;
        });
    }

    @ParameterizedTest
    @CsvSource({"reserve,false", "confirm,false", "release,false",
            "reserve,true", "confirm,true", "release,true"})
    void retriesRealLockTimeoutAndKeepsChangesAtomic(String operation, boolean exhaustRetries) throws Exception {
        Product product = product();
        UUID orderId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        if (!operation.equals("reserve")) {
            service.reserve(orderId, List.of(new StockItemRequestDTO(product.getId(), 2)));
            reservationId = reservations.findByOrderId(orderId).orElseThrow().getId();
        }
        String payload = payload(operation, orderId, product.getId(), reservationId, 2);

        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (var statement = connection.prepareStatement(
                        "update products set quantity_reserved = quantity_reserved where id = ?")) {
                    statement.setObject(1, product.getId());
                    assertThat(statement.executeUpdate()).isEqualTo(1);
                }
                publish(operation, payload);
                if (exhaustRetries) {
                    await(() -> deadLetters(operation) == 1);
                    assertBackoff();
                    assertStock(product, 5, operation.equals("reserve") ? 0 : 2);
                    assertThat(events.count()).isZero();
                    if (operation.equals("reserve")) {
                        assertThat(reservations.count()).isZero();
                    } else {
                        assertThat(reservations.findByOrderId(orderId).orElseThrow().getStatus())
                                .isEqualTo(ReservationStatus.RESERVED);
                    }
                } else {
                    await(() -> attempts.size() == 3);
                    connection.rollback();
                    await(() -> reservations.findByOrderId(orderId)
                            .map(reservation -> reservation.getStatus() == finalStatus(operation)).orElse(false));
                    assertBackoff();
                    assertThat(deadLetters(operation)).isZero();
                    assertFinished(operation, product, orderId);

                    publish(operation, payload);
                    // Marcador inválido após a duplicata, no mesmo consumidor.
                    publish(operation, "{}");
                    await(() -> deadLetters(operation) == 1);
                    assertThat(attempts).hasSize(5);
                    assertFinished(operation, product, orderId);
                }
            } finally {
                connection.rollback();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"reserve", "confirm", "release"})
    void invalidEventGoesDirectlyToDeadLetter(String operation) throws Exception {
        publish(operation, "{}");
        await(() -> deadLetters(operation) == 1);
        assertThat(attempts).hasSize(1);
        assertThat(events.count()).isZero();
        assertThat(reservations.count()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"reserve", "confirm", "release"})
    void manuallyRecoversDeadLetterOnlyToOriginalQueueWithoutDuplicatingStock(String operation) throws Exception {
        Product product = product();
        UUID orderId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        if (!operation.equals("reserve")) {
            service.reserve(orderId, List.of(new StockItemRequestDTO(product.getId(), 2)));
            reservationId = reservations.findByOrderId(orderId).orElseThrow().getId();
        }
        String payload = payload(operation, orderId, product.getId(), reservationId, 2);
        String eventId = jsonMapper.readTree(payload).get("eventId").asText();
        Message original = recoveryMessage(payload.getBytes(StandardCharsets.UTF_8), eventId);
        String exchange = switch (operation) {
            case "reserve" -> RabbitMQConfig.ORDER_CREATED_EXCHANGE;
            case "confirm" -> RabbitMQConfig.PAYMENT_APPROVED_EXCHANGE;
            default -> RabbitMQConfig.PAYMENT_REFUSED_EXCHANGE;
        };
        String otherSubscriber = rabbit.execute(channel -> {
            String name = channel.queueDeclare("", false, true, false, null).getQueue();
            channel.queueBind(name, exchange, "");
            return name;
        });

        try {
            try (var connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                try {
                    try (var statement = connection.prepareStatement(
                            "update products set quantity_reserved = quantity_reserved where id = ?")) {
                        statement.setObject(1, product.getId());
                        assertThat(statement.executeUpdate()).isEqualTo(1);
                    }
                    rabbit.send(exchange, "", original);
                    await(() -> deadLetters(operation) == 1);
                    assertBackoff();
                    assertStock(product, 5, operation.equals("reserve") ? 0 : 2);
                    assertThat(events.count()).isZero();
                    if (operation.equals("reserve")) {
                        assertThat(reservations.count()).isZero();
                    } else {
                        assertThat(reservations.findByOrderId(orderId).orElseThrow().getStatus())
                                .isEqualTo(ReservationStatus.RESERVED);
                    }
                } finally {
                    // Corrigir a causa sem reiniciar a aplicação nem recriar os dados.
                    connection.rollback();
                }
            }
            Message subscriberCopy = rabbit.receive(otherSubscriber, 5000);
            assertThat(subscriberCopy).isNotNull();
            assertThat(subscriberCopy.getBody()).isEqualTo(original.getBody());

            Message recovery = rabbit.execute(channel -> {
                var deadLetter = channel.basicGet(dlq(operation), false);
                assertThat(deadLetter).isNotNull();
                channel.basicNack(deadLetter.getEnvelope().getDeliveryTag(), false, true);
                assertThat(deadLetter.getProps().getHeaders()).containsKey("x-death");
                return recoveryMessage(deadLetter.getBody(), deadLetter.getProps().getMessageId());
            });
            assertThat(recovery.getBody()).isEqualTo(original.getBody());
            assertThat(recovery.getMessageProperties().getMessageId()).isEqualTo(eventId);
            await(() -> deadLetters(operation) == 1);

            rabbit.send("", queue(operation), recovery);
            await(() -> reservations.findByOrderId(orderId)
                    .map(reservation -> reservation.getStatus() == finalStatus(operation)).orElse(false));
            assertFinished(operation, product, orderId);
            UUID recoveredReservationId = reservations.findByOrderId(orderId).orElseThrow().getId();
            if (!operation.equals("reserve")) {
                assertThat(recoveredReservationId).isEqualTo(reservationId);
            }
            var resultIds = events.findAll().stream().map(event -> event.getEventId()).toList();
            if (operation.equals("reserve")) {
                assertThat(events.findAll().getFirst().getSourceEventId()).isEqualTo(UUID.fromString(eventId));
            }
            assertThat(deadLetters(operation)).isEqualTo(1);

            rabbit.send("", queue(operation), recovery);
            // Marcador no mesmo consumidor: chegar à DLQ comprova o término da duplicata.
            // Também representa outra mensagem que a retirada da cópia recuperada deve preservar.
            publish(operation, "{}");
            await(() -> deadLetters(operation) == 2);
            assertThat(attempts).hasSize(6);
            assertFinished(operation, product, orderId);
            assertThat(reservations.findByOrderId(orderId).orElseThrow().getId()).isEqualTo(recoveredReservationId);
            assertThat(events.findAll().stream().map(event -> event.getEventId()).toList()).isEqualTo(resultIds);
            assertThat(rabbit.receive(otherSubscriber)).isNull();

            // ACK individual somente após resultado persistido e identidade conferida.
            rabbit.execute(channel -> {
                var removed = channel.basicGet(dlq(operation), false);
                assertThat(removed).isNotNull();
                try {
                    assertThat(removed.getBody()).isEqualTo(original.getBody());
                    assertThat(removed.getProps().getMessageId()).isEqualTo(eventId);
                } catch (AssertionError error) {
                    channel.basicNack(removed.getEnvelope().getDeliveryTag(), false, true);
                    throw error;
                }
                channel.basicAck(removed.getEnvelope().getDeliveryTag(), false);
                var remaining = channel.basicGet(dlq(operation), false);
                assertThat(remaining).isNotNull();
                channel.basicNack(remaining.getEnvelope().getDeliveryTag(), false, true);
                assertThat(new String(remaining.getBody(), StandardCharsets.UTF_8)).isEqualTo("{}");
                return null;
            });
            await(() -> deadLetters(operation) == 1);
        } finally {
            rabbit.execute(channel -> channel.queueDelete(otherSubscriber));
        }
    }

    private Message recoveryMessage(byte[] body, String eventId) {
        // Como no painel: manter bytes e identidade, sem reutilizar headers de dead-letter.
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setContentEncoding(StandardCharsets.UTF_8.name());
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        properties.setMessageId(eventId);
        return new Message(body, properties);
    }

    @Test
    void insufficientStockPublishesFailureWithoutRetry() throws Exception {
        Product product = product();
        publish("reserve", payload("reserve", UUID.randomUUID(), product.getId(), UUID.randomUUID(), 6));
        await(() -> events.count() == 1);
        assertThat(events.findAll().getFirst().getExchange()).isEqualTo(RabbitMQConfig.STOCK_RESERVATION_FAILED_EXCHANGE);
        assertThat(attempts).hasSize(1);
        assertThat(deadLetters("reserve")).isZero();
        assertThat(reservations.count()).isZero();
        assertStock(product, 5, 0);
    }

    @Test
    void retriesFailureToOpenTransaction() throws Exception {
        Product product = product();
        UUID orderId = UUID.randomUUID();
        temporaryFailure = new CannotCreateTransactionException("Banco temporariamente indisponível");
        publish("reserve", payload("reserve", orderId, product.getId(), UUID.randomUUID(), 2));
        await(() -> events.count() == 1);
        assertBackoff();
        assertFinished("reserve", product, orderId);
        assertThat(deadLetters("reserve")).isZero();
    }

    private Product product() {
        return products.saveAndFlush(new Product(null, "Produto de teste", null,
                BigDecimal.TEN, null, 5, 0, UUID.randomUUID(), true));
    }

    private String payload(String operation, UUID orderId, UUID productId, UUID reservationId, int quantity) {
        String data = operation.equals("reserve")
                ? "\"items\":[{\"productId\":\"" + productId + "\",\"quantity\":" + quantity + "}]"
                : "\"paymentId\":\"" + UUID.randomUUID() + "\",\"reservationId\":\"" + reservationId + "\"";
        return """
                {"eventId":"%s","orderId":"%s","occurredAt":"2026-10-19T12:00:00Z",
                 "amount":20.00,"currency":"BRL",%s}
                """.formatted(UUID.randomUUID(), orderId, data);
    }

    private String queue(String operation) {
        return switch (operation) {
            case "reserve" -> RabbitMQConfig.STOCK_ORDER_CREATED_QUEUE;
            case "confirm" -> RabbitMQConfig.STOCK_PAYMENT_APPROVED_QUEUE;
            default -> RabbitMQConfig.STOCK_PAYMENT_REFUSED_QUEUE;
        };
    }

    private String dlq(String operation) {
        return queue(operation).replace(".queue", ".dlq");
    }

    private void publish(String operation, String payload) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        rabbit.send("", queue(operation), new Message(payload.getBytes(StandardCharsets.UTF_8), properties));
    }

    private long deadLetters(String operation) {
        return rabbit.execute(channel -> channel.messageCount(dlq(operation)));
    }

    private ReservationStatus finalStatus(String operation) {
        return switch (operation) {
            case "reserve" -> ReservationStatus.RESERVED;
            case "confirm" -> ReservationStatus.CONFIRMED;
            default -> ReservationStatus.RELEASED;
        };
    }

    private void assertFinished(String operation, Product product, UUID orderId) {
        assertStock(product, operation.equals("confirm") ? 3 : 5, operation.equals("reserve") ? 2 : 0);
        assertThat(reservations.count()).isEqualTo(1);
        assertThat(reservations.findByOrderId(orderId).orElseThrow().getStatus()).isEqualTo(finalStatus(operation));
        assertThat(events.count()).isEqualTo(operation.equals("reserve") ? 1 : 0);
    }

    private void assertStock(Product product, int available, int reserved) {
        Product saved = products.findById(product.getId()).orElseThrow();
        assertThat(saved.getQuantityAvailable()).isEqualTo(available);
        assertThat(saved.getQuantityReserved()).isEqualTo(reserved);
    }

    private void assertBackoff() {
        assertThat(attempts).hasSize(3);
        assertThat(TimeUnit.NANOSECONDS.toMillis(attempts.get(1) - attempts.get(0))).isGreaterThanOrEqualTo(1000);
        assertThat(TimeUnit.NANOSECONDS.toMillis(attempts.get(2) - attempts.get(1))).isGreaterThanOrEqualTo(2000);
    }

    private void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }
}
