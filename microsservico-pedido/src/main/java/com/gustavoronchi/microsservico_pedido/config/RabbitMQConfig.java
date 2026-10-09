package com.gustavoronchi.microsservico_pedido.config;

import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
public class RabbitMQConfig {

    public static final String ORDER_CREATED_EXCHANGE = "order.created";
    public static final String STOCK_RESERVED_EXCHANGE = "stock.reserved";
    public static final String ORDER_STOCK_RESERVED_QUEUE = "order.stock.reserved.queue";
    public static final String ORDER_STOCK_RESERVED_DLQ = "order.stock.reserved.dlq";
    public static final String ORDER_STOCK_RESERVED_DLX = "order.stock.reserved.dlx";
    public static final String STOCK_RESERVATION_FAILED_EXCHANGE = "stock.reservation.failed";
    public static final String ORDER_STOCK_RESERVATION_FAILED_QUEUE = "order.stock.reservation.failed.queue";
    public static final String ORDER_STOCK_RESERVATION_FAILED_DLQ = "order.stock.reservation.failed.dlq";
    public static final String ORDER_STOCK_RESERVATION_FAILED_DLX = "order.stock.reservation.failed.dlx";
    public static final String PAYMENT_APPROVED_EXCHANGE = "payment.approved";
    public static final String ORDER_PAYMENT_APPROVED_QUEUE = "order.payment.approved.queue";
    public static final String ORDER_PAYMENT_APPROVED_DLQ = "order.payment.approved.dlq";
    public static final String ORDER_PAYMENT_APPROVED_DLX = "order.payment.approved.dlx";
    public static final String PAYMENT_REFUSED_EXCHANGE = "payment.refused";
    public static final String ORDER_PAYMENT_REFUSED_QUEUE = "order.payment.refused.queue";
    public static final String ORDER_PAYMENT_REFUSED_DLQ = "order.payment.refused.dlq";
    public static final String ORDER_PAYMENT_REFUSED_DLX = "order.payment.refused.dlx";
    public static final String STOCK_RELEASE_EXCHANGE = "stock.release";

    @Bean
    public FanoutExchange orderCreatedExchange() {
        return new FanoutExchange(ORDER_CREATED_EXCHANGE);
    }

    @Bean
    public FanoutExchange stockReservedExchange() {
        return new FanoutExchange(STOCK_RESERVED_EXCHANGE);
    }

    @Bean
    public FanoutExchange stockReservationFailedExchange() {
        return new FanoutExchange(STOCK_RESERVATION_FAILED_EXCHANGE);
    }

    @Bean
    public Queue orderStockReservationFailedQueue() {
        return QueueBuilder.durable(ORDER_STOCK_RESERVATION_FAILED_QUEUE)
                .withArgument("x-dead-letter-exchange", ORDER_STOCK_RESERVATION_FAILED_DLX)
                .build();
    }

    @Bean
    public FanoutExchange orderStockReservationFailedDlx() {
        return new FanoutExchange(ORDER_STOCK_RESERVATION_FAILED_DLX);
    }

    @Bean
    public Queue orderStockReservationFailedDlq() {
        return QueueBuilder.durable(ORDER_STOCK_RESERVATION_FAILED_DLQ).build();
    }

    @Bean
    public Binding bindOrderStockReservationFailedQueue() {
        return BindingBuilder.bind(orderStockReservationFailedQueue()).to(stockReservationFailedExchange());
    }

    @Bean
    public Binding bindOrderStockReservationFailedDlq() {
        return BindingBuilder.bind(orderStockReservationFailedDlq()).to(orderStockReservationFailedDlx());
    }

    @Bean
    public Queue orderStockReservedQueue() {
        return QueueBuilder.durable(ORDER_STOCK_RESERVED_QUEUE)
                .withArgument("x-dead-letter-exchange", ORDER_STOCK_RESERVED_DLX)
                .build();
    }

    @Bean
    public FanoutExchange orderStockReservedDlx() {
        return new FanoutExchange(ORDER_STOCK_RESERVED_DLX);
    }

    @Bean
    public Queue orderStockReservedDlq() {
        return QueueBuilder.durable(ORDER_STOCK_RESERVED_DLQ).build();
    }

    @Bean
    public Binding bindOrderStockReservedQueue() {
        return BindingBuilder.bind(orderStockReservedQueue()).to(stockReservedExchange());
    }

    @Bean
    public Binding bindOrderStockReservedDlq() {
        return BindingBuilder.bind(orderStockReservedDlq()).to(orderStockReservedDlx());
    }

    @Bean
    public FanoutExchange paymentApprovedExchange() {
        return new FanoutExchange(PAYMENT_APPROVED_EXCHANGE);
    }

    @Bean
    public Queue orderPaymentApprovedQueue() {
        return QueueBuilder.durable(ORDER_PAYMENT_APPROVED_QUEUE)
                .withArgument("x-dead-letter-exchange", ORDER_PAYMENT_APPROVED_DLX)
                .build();
    }

    @Bean
    public FanoutExchange orderPaymentApprovedDlx() {
        return new FanoutExchange(ORDER_PAYMENT_APPROVED_DLX);
    }

    @Bean
    public Queue orderPaymentApprovedDlq() {
        return QueueBuilder.durable(ORDER_PAYMENT_APPROVED_DLQ).build();
    }

    @Bean
    public Binding bindOrderPaymentApprovedQueue() {
        return BindingBuilder.bind(orderPaymentApprovedQueue()).to(paymentApprovedExchange());
    }

    @Bean
    public Binding bindOrderPaymentApprovedDlq() {
        return BindingBuilder.bind(orderPaymentApprovedDlq()).to(orderPaymentApprovedDlx());
    }

    @Bean
    public FanoutExchange paymentRefusedExchange() {
        return new FanoutExchange(PAYMENT_REFUSED_EXCHANGE);
    }

    @Bean
    public Queue orderPaymentRefusedQueue() {
        return QueueBuilder.durable(ORDER_PAYMENT_REFUSED_QUEUE)
                .withArgument("x-dead-letter-exchange", ORDER_PAYMENT_REFUSED_DLX)
                .build();
    }

    @Bean
    public FanoutExchange orderPaymentRefusedDlx() {
        return new FanoutExchange(ORDER_PAYMENT_REFUSED_DLX);
    }

    @Bean
    public Queue orderPaymentRefusedDlq() {
        return QueueBuilder.durable(ORDER_PAYMENT_REFUSED_DLQ).build();
    }

    @Bean
    public Binding bindOrderPaymentRefusedQueue() {
        return BindingBuilder.bind(orderPaymentRefusedQueue()).to(paymentRefusedExchange());
    }

    @Bean
    public Binding bindOrderPaymentRefusedDlq() {
        return BindingBuilder.bind(orderPaymentRefusedDlq()).to(orderPaymentRefusedDlx());
    }

    @Bean
    public FanoutExchange stockReleaseExchange() {
        return new FanoutExchange(STOCK_RELEASE_EXCHANGE);
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new JacksonJsonMessageConverter();
    }
}
