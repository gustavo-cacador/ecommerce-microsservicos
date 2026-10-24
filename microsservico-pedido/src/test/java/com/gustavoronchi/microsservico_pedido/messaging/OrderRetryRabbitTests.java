package com.gustavoronchi.microsservico_pedido.messaging;

import com.gustavoronchi.microsservico_pedido.config.RabbitMQConfig;
import com.gustavoronchi.microsservico_pedido.domain.entities.Order;
import com.gustavoronchi.microsservico_pedido.domain.repository.OrderRepository;
import com.gustavoronchi.microsservico_pedido.domain.repository.OutboxEventRepository;
import com.gustavoronchi.microsservico_pedido.enums.StatusOrder;
import org.junit.jupiter.api.BeforeEach;
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
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

// Executar somente com um broker isolado: este teste limpa suas filas nesse broker.
@EnabledIfEnvironmentVariable(named = "RABBITMQ_TEST_PORT", matches = "\\d+")
@SpringBootTest(properties = {
        "spring.rabbitmq.port=${RABBITMQ_TEST_PORT}",
        "spring.datasource.url=jdbc:h2:mem:pedido-retry-test;LOCK_TIMEOUT=200",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class OrderRetryRabbitTests {

    @Autowired
    private RabbitTemplate rabbit;
    @Autowired
    private OrderRepository orders;
    @Autowired
    private OutboxEventRepository events;
    @Autowired
    private JsonMapper jsonMapper;
    @MockitoBean
    private OutboxRelay outboxRelay;
    @MockitoSpyBean
    private DataSource dataSource;
    @MockitoSpyBean
    private StockReservedListener reservedListener;
    @MockitoSpyBean
    private StockReservationFailedListener failedListener;
    @MockitoSpyBean
    private PaymentApprovedListener approvedListener;
    @MockitoSpyBean
    private PaymentRefusedListener refusedListener;

    private final List<Long> attempts = new CopyOnWriteArrayList<>();
    private final ThreadLocal<Boolean> inListener = ThreadLocal.withInitial(() -> false);
    private final AtomicInteger connectionFailures = new AtomicInteger();
    private volatile int failConnectionsUntilAttempt;

    @BeforeEach
    void setUp() throws Exception {
        failConnectionsUntilAttempt = 0;
        connectionFailures.set(0);
        events.deleteAll();
        orders.deleteAll();
        attempts.clear();
        Answer<Object> recordAttempt = invocation -> {
            attempts.add(System.nanoTime());
            inListener.set(true);
            try {
                return invocation.callRealMethod();
            } finally {
                inListener.remove();
            }
        };
        doAnswer(recordAttempt).when(reservedListener).hearStockReserved(any());
        doAnswer(recordAttempt).when(failedListener).hearStockReservationFailed(any());
        doAnswer(recordAttempt).when(approvedListener).hearPaymentApproved(any());
        doAnswer(recordAttempt).when(refusedListener).hearPaymentRefused(any());
        // A falha ocorre ao abrir a transação real, sem substituir o serviço transacional.
        doAnswer(invocation -> {
            if (inListener.get() && attempts.size() <= failConnectionsUntilAttempt) {
                connectionFailures.incrementAndGet();
                throw new SQLException("Banco temporariamente indisponível");
            }
            return invocation.callRealMethod();
        }).when(dataSource).getConnection();
        rabbit.execute(channel -> {
            for (String operation : List.of("reserved", "failed", "approved", "refused")) {
                channel.queuePurge(queue(operation));
                channel.queuePurge(dlq(operation));
            }
            return null;
        });
    }

    @ParameterizedTest
    @CsvSource({"reserved,false", "failed,false", "approved,false", "refused,false",
            "reserved,true", "failed,true", "approved,true", "refused,true"})
    void retriesRealLockTimeoutAndKeepsChangesAtomic(String operation, boolean exhaustRetries) throws Exception {
        Order order = order(StatusOrder.CREATED);
        String payload = payload(order.getId(), "10.00");
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (var statement = connection.prepareStatement("update orders set status = status where id = ?")) {
                    statement.setObject(1, order.getId());
                    assertThat(statement.executeUpdate()).isEqualTo(1);
                }
                publish(operation, payload);
                if (exhaustRetries) {
                    await(() -> deadLetters(operation) == 1);
                    assertUnchanged(order);
                } else {
                    await(() -> attempts.size() == 3);
                    connection.rollback();
                    await(() -> saved(order).getStatus() == finalStatus(operation));
                    assertThat(deadLetters(operation)).isZero();
                }
                assertBackoff();
                assertThat(events.count()).isZero();
            } finally {
                connection.rollback();
            }
        }
        if (!exhaustRetries) {
            Order finished = saved(order);
            publish(operation, payload);
            publish(operation, "{}"); // Marcador após a duplicata, no mesmo consumidor.
            await(() -> deadLetters(operation) == 1);
            assertThat(attempts).hasSize(5);
            assertUnchanged(finished);
        }
    }

    @ParameterizedTest
    @CsvSource({"reserved,false", "failed,false", "approved,false", "refused,false",
            "reserved,true", "failed,true", "approved,true", "refused,true"})
    void retriesFailureToOpenTransaction(String operation, boolean exhaustRetries) throws Exception {
        Order order = order(StatusOrder.CREATED);
        failConnectionsUntilAttempt = exhaustRetries ? 3 : 2;
        publish(operation, payload(order.getId(), "10.00"));
        if (exhaustRetries) {
            await(() -> deadLetters(operation) == 1);
            assertUnchanged(order);
        } else {
            await(() -> saved(order).getStatus() == finalStatus(operation));
            assertThat(deadLetters(operation)).isZero();
        }
        assertBackoff();
        assertThat(connectionFailures.get()).isEqualTo(failConnectionsUntilAttempt);
        assertThat(events.count()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"reserved", "failed", "approved", "refused"})
    void invalidEventAndUnknownOrderGoDirectlyToDeadLetter(String operation) throws Exception {
        Order order = order(StatusOrder.CREATED);
        publish(operation, "{}");
        await(() -> deadLetters(operation) == 1);
        assertThat(attempts).hasSize(1);
        publish(operation, payload(UUID.randomUUID(), "10.00"));
        await(() -> deadLetters(operation) == 2);
        assertThat(attempts).hasSize(2);
        assertUnchanged(order);
    }

    @ParameterizedTest
    @ValueSource(strings = {"reserved", "approved", "refused"})
    void inconsistentAmountGoesDirectlyToDeadLetter(String operation) throws Exception {
        Order order = order(StatusOrder.CREATED);
        publish(operation, payload(order.getId(), "99.00"));
        await(() -> deadLetters(operation) == 1);
        assertThat(attempts).hasSize(1);
        assertUnchanged(order);
    }

    @ParameterizedTest
    @CsvSource({"reserved,PAID", "failed,PAID", "approved,PAID", "refused,PAID",
            "reserved,CANCELED", "failed,CANCELED", "approved,CANCELED", "refused,CANCELED",
            "failed,WAITING_PAYMENT"})
    void retryPreservesFinalStatesAndIgnoresLateStockFailure(String operation, StatusOrder status) throws Exception {
        Order order = order(status);
        failConnectionsUntilAttempt = 2;
        publish(operation, payload(order.getId(), "10.00"));
        publish(operation, "{}");
        await(() -> deadLetters(operation) == 1);
        assertThat(attempts).hasSize(4); // Três tentativas do evento e uma do marcador inválido.
        assertBackoffIntervals();
        assertThat(connectionFailures.get()).isEqualTo(2);
        assertUnchanged(order);
    }

    @ParameterizedTest
    @CsvSource({"reserved,CREATED", "failed,CREATED", "approved,CREATED", "refused,CREATED",
            "reserved,PAID", "failed,PAID", "approved,PAID", "refused,PAID",
            "reserved,CANCELED", "failed,CANCELED", "approved,CANCELED", "refused,CANCELED",
            "failed,WAITING_PAYMENT"})
    void manuallyRecoversDeadLetterOnlyToOriginalQueueWithoutRepeatingEffects(
            String operation, StatusOrder status) throws Exception {
        Order order = order(status);
        String payload = payload(order.getId(), "10.00");
        String eventId = jsonMapper.readTree(payload).get("eventId").asText();
        Message original = recoveryMessage(payload.getBytes(StandardCharsets.UTF_8), eventId);
        String exchange = switch (operation) {
            case "reserved" -> RabbitMQConfig.STOCK_RESERVED_EXCHANGE;
            case "failed" -> RabbitMQConfig.STOCK_RESERVATION_FAILED_EXCHANGE;
            case "approved" -> RabbitMQConfig.PAYMENT_APPROVED_EXCHANGE;
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
                    try (var statement = connection.prepareStatement("update orders set status = status where id = ?")) {
                        statement.setObject(1, order.getId());
                        assertThat(statement.executeUpdate()).isEqualTo(1);
                    }
                    rabbit.send(exchange, "", original);
                    await(() -> deadLetters(operation) == 1);
                    assertBackoff();
                    assertUnchanged(order);
                } finally {
                    // Corrigir a causa sem reiniciar a aplicação nem recriar o pedido.
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
            rabbit.send("", queue(operation), recovery);
            // Marcador no mesmo consumidor comprova o término dos dois reenvios.
            // Outra mensagem que o ACK da cópia recuperada deve preservar.
            publish(operation, "{}");
            await(() -> deadLetters(operation) == 2);
            assertThat(attempts).hasSize(6);
            Order recovered = saved(order);
            if (status == StatusOrder.CREATED) {
                assertThat(recovered.getStatus()).isEqualTo(finalStatus(operation));
                assertThat(recovered.getUpdatedAt()).isAfter(order.getUpdatedAt());
            } else {
                assertUnchanged(order);
            }
            assertThat(recovered.getIdempotencyKey()).isEqualTo(order.getIdempotencyKey());
            assertThat(recovered.getTotalValue()).isEqualByComparingTo(order.getTotalValue());
            assertThat(orders.count()).isEqualTo(1);
            assertThat(events.count()).isZero();
            assertThat(rabbit.receive(otherSubscriber)).isNull();

            // Nova duplicata após capturar o resultado persistido: nem updatedAt pode mudar.
            rabbit.send("", queue(operation), recovery);
            publish(operation, "{}");
            await(() -> deadLetters(operation) == 3);
            assertThat(attempts).hasSize(8);
            assertUnchanged(recovered);
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
            await(() -> deadLetters(operation) == 2);
        } finally {
            rabbit.execute(channel -> channel.queueDelete(otherSubscriber));
        }
    }

    private Message recoveryMessage(byte[] body, String eventId) {
        // Como no painel: manter bytes e identidade, sem headers da entrega anterior.
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setContentEncoding(StandardCharsets.UTF_8.name());
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        properties.setMessageId(eventId);
        return new Message(body, properties);
    }

    private Order order(StatusOrder status) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Order order = new Order(UUID.randomUUID(), UUID.randomUUID(), status, BigDecimal.TEN, now, now);
        order.setIdempotencyKey(UUID.randomUUID().toString());
        return orders.saveAndFlush(order);
    }

    private Order saved(Order order) {
        return orders.findById(order.getId()).orElseThrow();
    }

    private void assertUnchanged(Order order) {
        Order saved = saved(order);
        assertThat(saved.getStatus()).isEqualTo(order.getStatus());
        assertThat(saved.getUpdatedAt()).isEqualTo(order.getUpdatedAt());
        assertThat(saved.getIdempotencyKey()).isEqualTo(order.getIdempotencyKey());
        assertThat(saved.getTotalValue()).isEqualByComparingTo(order.getTotalValue());
        assertThat(orders.count()).isEqualTo(1);
        assertThat(events.count()).isZero();
    }

    private String payload(UUID orderId, String amount) {
        return """
                {"eventId":"%s","orderId":"%s","occurredAt":"2026-10-20T12:00:00Z",
                 "paymentId":"%s","reservationId":"%s","amount":%s,"currency":"BRL",
                 "failureReason":"Estoque insuficiente"}
                """.formatted(UUID.randomUUID(), orderId, UUID.randomUUID(), UUID.randomUUID(), amount);
    }

    private String queue(String operation) {
        return switch (operation) {
            case "reserved" -> RabbitMQConfig.ORDER_STOCK_RESERVED_QUEUE;
            case "failed" -> RabbitMQConfig.ORDER_STOCK_RESERVATION_FAILED_QUEUE;
            case "approved" -> RabbitMQConfig.ORDER_PAYMENT_APPROVED_QUEUE;
            default -> RabbitMQConfig.ORDER_PAYMENT_REFUSED_QUEUE;
        };
    }

    private String dlq(String operation) {
        return queue(operation).replace(".queue", ".dlq");
    }

    private StatusOrder finalStatus(String operation) {
        return switch (operation) {
            case "reserved" -> StatusOrder.WAITING_PAYMENT;
            case "approved" -> StatusOrder.PAID;
            default -> StatusOrder.CANCELED;
        };
    }

    private void publish(String operation, String payload) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        rabbit.send("", queue(operation), new Message(payload.getBytes(StandardCharsets.UTF_8), properties));
    }

    private long deadLetters(String operation) {
        return rabbit.execute(channel -> channel.messageCount(dlq(operation)));
    }

    private void assertBackoff() {
        assertThat(attempts).hasSize(3);
        assertBackoffIntervals();
    }

    private void assertBackoffIntervals() {
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
