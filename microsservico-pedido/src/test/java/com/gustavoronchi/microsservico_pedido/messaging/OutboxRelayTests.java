package com.gustavoronchi.microsservico_pedido.messaging;

import com.gustavoronchi.microsservico_pedido.domain.entities.OutboxEvent;
import com.gustavoronchi.microsservico_pedido.domain.repository.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DataJpaTest(showSql = false, properties = "spring.sql.init.mode=never")
@Import(OutboxRelay.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OutboxRelayTests {

    @Autowired
    private OutboxRelay relay;
    @MockitoSpyBean
    private OutboxEventRepository events;
    @MockitoBean
    private RabbitTemplate rabbitTemplate;

    @BeforeEach
    void setUp() {
        events.deleteAll();
    }

    @Test
    void marksPublishedOnlyAfterConfirmationAndDoesNotSendAgain() {
        OutboxEvent event = event();
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertPending(event);
            Message message = invocation.getArgument(2);
            assertThat(new String(message.getBody(), StandardCharsets.UTF_8)).isEqualTo(event.getPayload());
            assertThat(message.getMessageProperties().getMessageId()).isEqualTo(event.getEventId().toString());
            assertThat(message.getMessageProperties().getDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
            assertThat(message.getMessageProperties().getContentType()).isEqualTo(MessageProperties.CONTENT_TYPE_JSON);
            CorrelationData correlation = invocation.getArgument(3);
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbitTemplate).send(any(String.class), any(String.class), any(Message.class), any(CorrelationData.class));

        relay.publishPending();
        relay.publishPending();

        assertThat(events.findById(event.getEventId()).orElseThrow().getPublishedAt()).isNotNull();
        verify(rabbitTemplate, times(1)).send(any(String.class), any(String.class), any(Message.class), any(CorrelationData.class));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void nackOrReturnedMessageRemainsPending(boolean returned) {
        OutboxEvent event = event();
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(3);
            if (returned) {
                correlation.setReturned(new ReturnedMessage(invocation.getArgument(2), 312, "NO_ROUTE", "order.created", ""));
            }
            correlation.getFuture().complete(new CorrelationData.Confirm(returned, returned ? null : "NACK"));
            return null;
        }).when(rabbitTemplate).send(any(String.class), any(String.class), any(Message.class), any(CorrelationData.class));

        relay.publishPending();

        assertPending(event);
    }

    @Test
    void timeoutAndLateAckAllowRetryWithSameEventAndFreshCorrelation() {
        OutboxEvent event = event();
        List<CorrelationData> attempts = new ArrayList<>();
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(3);
            attempts.add(correlation);
            Message message = invocation.getArgument(2);
            assertThat(message.getMessageProperties().getMessageId()).isEqualTo(event.getEventId().toString());
            assertThat(new String(message.getBody(), StandardCharsets.UTF_8)).isEqualTo(event.getPayload());
            if (attempts.size() > 1) {
                correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            }
            return null;
        }).when(rabbitTemplate).send(any(String.class), any(String.class), any(Message.class), any(CorrelationData.class));

        new OutboxRelay(events, rabbitTemplate, 1).publishPending();
        attempts.getFirst().getFuture().complete(new CorrelationData.Confirm(true, null));
        assertPending(event);
        relay.publishPending();

        assertThat(attempts).hasSize(2);
        assertThat(attempts.get(0).getId()).isNotEqualTo(attempts.get(1).getId());
        assertThat(events.findById(event.getEventId()).orElseThrow().getPublishedAt()).isNotNull();
    }

    @Test
    void connectionFailureLeavesEventPending() {
        OutboxEvent event = event();
        doThrow(new AmqpException("Conexão indisponível")).when(rabbitTemplate)
                .send(any(String.class), any(String.class), any(Message.class), any(CorrelationData.class));

        relay.publishPending();

        assertPending(event);
    }

    @Test
    void databaseFailureAfterAckLeavesEventPendingForRedelivery() {
        OutboxEvent event = event();
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(3);
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbitTemplate).send(any(String.class), any(String.class), any(Message.class), any(CorrelationData.class));
        doThrow(new DataAccessResourceFailureException("Banco indisponível")).when(events).markPublished(any(), any());

        relay.publishPending();

        assertPending(event);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "RABBITMQ_TEST_PORT", matches = "[0-9]+")
    void realBrokerConfirmsRoutedMessageAndReturnsUnroutableMessage() {
        CachingConnectionFactory connection = new CachingConnectionFactory("127.0.0.1",
                Integer.parseInt(System.getenv("RABBITMQ_TEST_PORT")));
        connection.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        connection.setPublisherReturns(true);
        RabbitTemplate template = new RabbitTemplate(connection);
        template.setMandatory(true);
        RabbitAdmin admin = new RabbitAdmin(connection);
        String name = "outbox-test-" + UUID.randomUUID();
        try {
            admin.declareExchange(new DirectExchange(name));
            admin.declareExchange(new FanoutExchange(name + ".dlx"));
            admin.declareQueue(QueueBuilder.durable(name + ".dlq").build());
            admin.declareBinding(new Binding(name + ".dlq", Binding.DestinationType.QUEUE, name + ".dlx", "", null));
            admin.declareQueue(QueueBuilder.durable(name).deadLetterExchange(name + ".dlx").build());
            admin.declareBinding(new Binding(name, Binding.DestinationType.QUEUE, name, "confirm", null));
            OutboxEvent routed = event();
            routed.setExchange(name);
            routed.setRoutingKey("confirm");
            events.saveAndFlush(routed);
            OutboxEvent unroutable = event();
            unroutable.setExchange(name);
            unroutable.setRoutingKey("missing");
            events.saveAndFlush(unroutable);

            new OutboxRelay(events, template, 5000).publishPending();

            assertThat(events.findById(routed.getEventId()).orElseThrow().getPublishedAt()).isNotNull();
            assertPending(unroutable);
            Message received = template.receive(name, 5000);
            assertThat(received).isNotNull();
            assertThat(new String(received.getBody(), StandardCharsets.UTF_8)).isEqualTo(routed.getPayload());
        } finally {
            try {
                admin.deleteQueue(name);
                admin.deleteQueue(name + ".dlq");
                admin.deleteExchange(name);
                admin.deleteExchange(name + ".dlx");
            } finally {
                connection.destroy();
            }
        }
    }

    private OutboxEvent event() {
        OutboxEvent event = new OutboxEvent();
        event.setOrderId(UUID.randomUUID());
        event.setOccurredAt(Instant.now());
        event.setExchange("order.created");
        event.setRoutingKey("");
        event.setPayload("{\"eventId\":\"" + event.getEventId() + "\",\"orderId\":\"" + event.getOrderId() + "\"}");
        return events.saveAndFlush(event);
    }

    private void assertPending(OutboxEvent event) {
        assertThat(events.findById(event.getEventId()).orElseThrow().getPublishedAt()).isNull();
    }
}
