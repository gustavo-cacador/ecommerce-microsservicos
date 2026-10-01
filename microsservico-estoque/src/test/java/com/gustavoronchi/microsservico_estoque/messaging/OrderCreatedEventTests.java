package com.gustavoronchi.microsservico_estoque.messaging;

import com.gustavoronchi.microsservico_estoque.config.RabbitMQConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class OrderCreatedEventTests {

    private static final String JSON = """
                {
                  "eventId": "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                  "orderId": "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
                  "occurredAt": "2026-09-30T12:00:00Z",
                  "items": [{"productId": "11111111-1111-1111-1111-111111111111", "quantity": 2}],
                  "amount": 200.00,
                  "currency": "BRL"
                }
                """;

    @Test
    void convertsProducerJsonWithoutJavaTypeHeaders() {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setInferredArgumentType(OrderCreatedEvent.class);
        Message message = new Message(JSON.getBytes(StandardCharsets.UTF_8), properties);

        Object converted = new RabbitMQConfig().jsonMessageConverter().fromMessage(message);

        assertThat(converted).isInstanceOf(OrderCreatedEvent.class);
        OrderCreatedEvent event = (OrderCreatedEvent) converted;
        assertThat(event.getEventId().toString()).isEqualTo("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
        assertThat(event.getOrderId().toString()).isEqualTo("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
        assertThat(event.getOccurredAt()).isEqualTo(Instant.parse("2026-09-30T12:00:00Z"));
        assertThat(event.getAmount()).isEqualByComparingTo("200.00");
        assertThat(event.getCurrency()).isEqualTo("BRL");
        assertThat(event.getItems()).singleElement().satisfies(item -> {
            assertThat(item.getProductId().toString()).isEqualTo("11111111-1111-1111-1111-111111111111");
            assertThat(item.getQuantity()).isEqualTo(2);
        });
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "RABBITMQ_TEST_PORT", matches = "[0-9]+")
    void routesOrderCreatedToStockAndRejectedMessageToDlq() throws Exception {
        CachingConnectionFactory connection = new CachingConnectionFactory("127.0.0.1",
                Integer.parseInt(System.getenv("RABBITMQ_TEST_PORT")));
        connection.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        RabbitAdmin admin = new RabbitAdmin(connection);
        RabbitMQConfig config = new RabbitMQConfig();
        try {
            admin.declareExchange(config.orderCreatedExchange());
            admin.declareExchange(config.stockOrderCreatedDlx());
            admin.declareQueue(config.stockOrderCreatedQueue());
            admin.declareQueue(config.stockOrderCreatedDlq());
            admin.declareBinding(config.bindStockOrderCreatedQueue());
            admin.declareBinding(config.bindStockOrderCreatedDlq());
            RabbitTemplate template = new RabbitTemplate(connection);
            MessageProperties properties = new MessageProperties();
            properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
            CorrelationData correlation = new CorrelationData();
            template.send(RabbitMQConfig.ORDER_CREATED_EXCHANGE, "",
                    new Message(JSON.getBytes(StandardCharsets.UTF_8), properties), correlation);
            assertThat(correlation.getFuture().get(5, java.util.concurrent.TimeUnit.SECONDS).ack()).isTrue();

            template.execute(channel -> {
                var delivery = channel.basicGet(RabbitMQConfig.STOCK_ORDER_CREATED_QUEUE, false);
                assertThat(delivery).isNotNull();
                assertThat(new String(delivery.getBody(), StandardCharsets.UTF_8)).isEqualTo(JSON);
                channel.basicReject(delivery.getEnvelope().getDeliveryTag(), false);
                return null;
            });

            Message deadLetter = template.receive(RabbitMQConfig.STOCK_ORDER_CREATED_DLQ, 5000);
            assertThat(deadLetter).isNotNull();
            assertThat(new String(deadLetter.getBody(), StandardCharsets.UTF_8)).isEqualTo(JSON);
            assertThat(deadLetter.getMessageProperties().getHeaders()).containsKey("x-death");
        } finally {
            connection.destroy();
        }
    }
}
