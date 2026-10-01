package com.gustavoronchi.microsservico_estoque.messaging;

import com.gustavoronchi.microsservico_estoque.config.RabbitMQConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class StockResultRoutingTests {

    @Test
    void resultQueuesAreDurableAndHaveDeadLetterRoutes() {
        RabbitMQConfig config = new RabbitMQConfig();
        assertThat(config.stockReservedExchange().isDurable()).isTrue();
        assertThat(config.stockReservationFailedExchange().isDurable()).isTrue();
        assertThat(config.orderStockReservedQueue().isDurable()).isTrue();
        assertThat(config.orderStockReservedQueue().getArguments())
                .containsEntry("x-dead-letter-exchange", RabbitMQConfig.ORDER_STOCK_RESERVED_DLX);
        assertThat(config.paymentStockReservedQueue().isDurable()).isTrue();
        assertThat(config.paymentStockReservedQueue().getArguments())
                .containsEntry("x-dead-letter-exchange", RabbitMQConfig.PAYMENT_STOCK_RESERVED_DLX);
        assertThat(config.orderStockReservationFailedQueue().isDurable()).isTrue();
        assertThat(config.orderStockReservationFailedQueue().getArguments())
                .containsEntry("x-dead-letter-exchange", RabbitMQConfig.ORDER_STOCK_RESERVATION_FAILED_DLX);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "RABBITMQ_TEST_PORT", matches = "[0-9]+")
    void routesReservationToBothServicesAndRejectedResultsToTheirDlqs() throws Exception {
        CachingConnectionFactory connection = new CachingConnectionFactory("127.0.0.1",
                Integer.parseInt(System.getenv("RABBITMQ_TEST_PORT")));
        connection.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        connection.setPublisherReturns(true);
        RabbitAdmin admin = new RabbitAdmin(connection);
        RabbitMQConfig config = new RabbitMQConfig();
        try {
            admin.declareExchange(config.stockReservedExchange());
            admin.declareExchange(config.stockReservationFailedExchange());
            for (FanoutExchange dlx : List.of(config.orderStockReservedDlx(), config.paymentStockReservedDlx(),
                    config.orderStockReservationFailedDlx())) {
                admin.declareExchange(dlx);
            }
            for (Queue queue : List.of(config.orderStockReservedQueue(), config.orderStockReservedDlq(),
                    config.paymentStockReservedQueue(), config.paymentStockReservedDlq(),
                    config.orderStockReservationFailedQueue(), config.orderStockReservationFailedDlq())) {
                admin.declareQueue(queue);
            }
            for (Binding binding : List.of(config.bindOrderStockReservedQueue(), config.bindOrderStockReservedDlq(),
                    config.bindPaymentStockReservedQueue(), config.bindPaymentStockReservedDlq(),
                    config.bindOrderStockReservationFailedQueue(), config.bindOrderStockReservationFailedDlq())) {
                admin.declareBinding(binding);
            }
            RabbitTemplate template = new RabbitTemplate(connection);
            template.setMandatory(true);
            for (String queue : List.of(RabbitMQConfig.ORDER_STOCK_RESERVED_QUEUE, RabbitMQConfig.PAYMENT_STOCK_RESERVED_QUEUE,
                    RabbitMQConfig.ORDER_STOCK_RESERVATION_FAILED_QUEUE)) {
                String exchange = queue.equals(RabbitMQConfig.ORDER_STOCK_RESERVATION_FAILED_QUEUE)
                        ? RabbitMQConfig.STOCK_RESERVATION_FAILED_EXCHANGE : RabbitMQConfig.STOCK_RESERVED_EXCHANGE;
                CorrelationData correlation = new CorrelationData();
                template.send(exchange, "", new Message("{}".getBytes(StandardCharsets.UTF_8)), correlation);
                assertThat(correlation.getFuture().get(5, TimeUnit.SECONDS).ack()).isTrue();
                assertThat(correlation.getReturned()).isNull();
                if (queue.equals(RabbitMQConfig.ORDER_STOCK_RESERVED_QUEUE)) {
                    Message paymentCopy = template.receive(RabbitMQConfig.PAYMENT_STOCK_RESERVED_QUEUE, 5000);
                    assertThat(paymentCopy).isNotNull();
                    assertThat(new String(paymentCopy.getBody(), StandardCharsets.UTF_8)).isEqualTo("{}");
                } else if (queue.equals(RabbitMQConfig.PAYMENT_STOCK_RESERVED_QUEUE)) {
                    template.receive(RabbitMQConfig.ORDER_STOCK_RESERVED_QUEUE, 5000);
                }
                template.execute(channel -> {
                    var delivery = channel.basicGet(queue, false);
                    assertThat(delivery).isNotNull();
                    assertThat(new String(delivery.getBody(), StandardCharsets.UTF_8)).isEqualTo("{}");
                    channel.basicReject(delivery.getEnvelope().getDeliveryTag(), false);
                    return null;
                });
                String dlq = queue.replace(".queue", ".dlq");
                Message rejected = template.receive(dlq, 5000);
                assertThat(rejected).isNotNull();
                assertThat(rejected.getMessageProperties().getHeaders()).containsKey("x-death");
            }
        } finally {
            connection.destroy();
        }
    }
}
