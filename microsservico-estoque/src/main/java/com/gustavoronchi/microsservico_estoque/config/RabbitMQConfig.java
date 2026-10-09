package com.gustavoronchi.microsservico_estoque.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
public class RabbitMQConfig {

    public static final String ORDER_CREATED_EXCHANGE = "order.created";
    public static final String STOCK_ORDER_CREATED_QUEUE = "stock.order.created.queue";
    public static final String STOCK_ORDER_CREATED_DLQ = "stock.order.created.dlq";
    public static final String STOCK_ORDER_CREATED_DLX = "stock.order.created.dlx";

    public static final String STOCK_RESERVED_EXCHANGE = "stock.reserved";
    public static final String ORDER_STOCK_RESERVED_QUEUE = "order.stock.reserved.queue";
    public static final String ORDER_STOCK_RESERVED_DLQ = "order.stock.reserved.dlq";
    public static final String ORDER_STOCK_RESERVED_DLX = "order.stock.reserved.dlx";
    public static final String PAYMENT_STOCK_RESERVED_QUEUE = "payment.stock.reserved.queue";
    public static final String PAYMENT_STOCK_RESERVED_DLQ = "payment.stock.reserved.dlq";
    public static final String PAYMENT_STOCK_RESERVED_DLX = "payment.stock.reserved.dlx";
    public static final String STOCK_RESERVATION_FAILED_EXCHANGE = "stock.reservation.failed";
    public static final String ORDER_STOCK_RESERVATION_FAILED_QUEUE = "order.stock.reservation.failed.queue";
    public static final String ORDER_STOCK_RESERVATION_FAILED_DLQ = "order.stock.reservation.failed.dlq";
    public static final String ORDER_STOCK_RESERVATION_FAILED_DLX = "order.stock.reservation.failed.dlx";

    public static final String STOCK_CONFIRM_EXCHANGE = "stock.confirm";
    public static final String STOCK_CONFIRM_QUEUE = "stock.confirm.queue";
    public static final String STOCK_CONFIRM_DLQ = "stock.confirm.dlq";
    public static final String STOCK_CONFIRM_DLX = "stock.confirm.dlx";

    public static final String STOCK_RELEASE_EXCHANGE = "stock.release";
    public static final String STOCK_RELEASE_QUEUE = "stock.release.queue";
    public static final String STOCK_RELEASE_DLQ = "stock.release.dlq";
    public static final String STOCK_RELEASE_DLX = "stock.release.dlx";

    public static final String PAYMENT_APPROVED_EXCHANGE = "payment.approved";
    public static final String STOCK_PAYMENT_APPROVED_QUEUE = "stock.payment.approved.queue";
    public static final String STOCK_PAYMENT_APPROVED_DLQ = "stock.payment.approved.dlq";
    public static final String STOCK_PAYMENT_APPROVED_DLX = "stock.payment.approved.dlx";

    public static final String PAYMENT_REFUSED_EXCHANGE = "payment.refused";
    public static final String STOCK_PAYMENT_REFUSED_QUEUE = "stock.payment.refused.queue";
    public static final String STOCK_PAYMENT_REFUSED_DLQ = "stock.payment.refused.dlq";
    public static final String STOCK_PAYMENT_REFUSED_DLX = "stock.payment.refused.dlx";

    @Bean
    public FanoutExchange paymentRefusedExchange() {
        return new FanoutExchange(PAYMENT_REFUSED_EXCHANGE);
    }

    @Bean
    public Queue stockPaymentRefusedQueue() {
        return QueueBuilder.durable(STOCK_PAYMENT_REFUSED_QUEUE)
                .withArgument("x-dead-letter-exchange", STOCK_PAYMENT_REFUSED_DLX)
                .build();
    }

    @Bean
    public FanoutExchange stockPaymentRefusedDlx() {
        return new FanoutExchange(STOCK_PAYMENT_REFUSED_DLX);
    }

    @Bean
    public Queue stockPaymentRefusedDlq() {
        return QueueBuilder.durable(STOCK_PAYMENT_REFUSED_DLQ).build();
    }

    @Bean
    public Binding bindStockPaymentRefusedQueue() {
        return BindingBuilder.bind(stockPaymentRefusedQueue()).to(paymentRefusedExchange());
    }

    @Bean
    public Binding bindStockPaymentRefusedDlq() {
        return BindingBuilder.bind(stockPaymentRefusedDlq()).to(stockPaymentRefusedDlx());
    }

    @Bean
    public FanoutExchange paymentApprovedExchange() {
        return new FanoutExchange(PAYMENT_APPROVED_EXCHANGE);
    }

    @Bean
    public Queue stockPaymentApprovedQueue() {
        return QueueBuilder.durable(STOCK_PAYMENT_APPROVED_QUEUE)
                .withArgument("x-dead-letter-exchange", STOCK_PAYMENT_APPROVED_DLX)
                .build();
    }

    @Bean
    public FanoutExchange stockPaymentApprovedDlx() {
        return new FanoutExchange(STOCK_PAYMENT_APPROVED_DLX);
    }

    @Bean
    public Queue stockPaymentApprovedDlq() {
        return QueueBuilder.durable(STOCK_PAYMENT_APPROVED_DLQ).build();
    }

    @Bean
    public Binding bindStockPaymentApprovedQueue() {
        return BindingBuilder.bind(stockPaymentApprovedQueue()).to(paymentApprovedExchange());
    }

    @Bean
    public Binding bindStockPaymentApprovedDlq() {
        return BindingBuilder.bind(stockPaymentApprovedDlq()).to(stockPaymentApprovedDlx());
    }

    @Bean
    public FanoutExchange orderCreatedExchange() {
        return new FanoutExchange(ORDER_CREATED_EXCHANGE);
    }

    @Bean
    public Queue stockOrderCreatedQueue() {
        return QueueBuilder.durable(STOCK_ORDER_CREATED_QUEUE)
                .withArgument("x-dead-letter-exchange", STOCK_ORDER_CREATED_DLX)
                .build();
    }

    @Bean
    public FanoutExchange stockOrderCreatedDlx() {
        return new FanoutExchange(STOCK_ORDER_CREATED_DLX);
    }

    @Bean
    public Queue stockOrderCreatedDlq() {
        return QueueBuilder.durable(STOCK_ORDER_CREATED_DLQ).build();
    }

    @Bean
    public Binding bindStockOrderCreatedQueue() {
        return BindingBuilder.bind(stockOrderCreatedQueue()).to(orderCreatedExchange());
    }

    @Bean
    public Binding bindStockOrderCreatedDlq() {
        return BindingBuilder.bind(stockOrderCreatedDlq()).to(stockOrderCreatedDlx());
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
    public Queue paymentStockReservedQueue() {
        return QueueBuilder.durable(PAYMENT_STOCK_RESERVED_QUEUE)
                .withArgument("x-dead-letter-exchange", PAYMENT_STOCK_RESERVED_DLX)
                .build();
    }

    @Bean
    public FanoutExchange paymentStockReservedDlx() {
        return new FanoutExchange(PAYMENT_STOCK_RESERVED_DLX);
    }

    @Bean
    public Queue paymentStockReservedDlq() {
        return QueueBuilder.durable(PAYMENT_STOCK_RESERVED_DLQ).build();
    }

    @Bean
    public Binding bindPaymentStockReservedQueue() {
        return BindingBuilder.bind(paymentStockReservedQueue()).to(stockReservedExchange());
    }

    @Bean
    public Binding bindPaymentStockReservedDlq() {
        return BindingBuilder.bind(paymentStockReservedDlq()).to(paymentStockReservedDlx());
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
    public FanoutExchange stockConfirmExchange() {
        return new FanoutExchange(STOCK_CONFIRM_EXCHANGE);
    }

    @Bean
    public Queue stockConfirmQueue() {
        return QueueBuilder.durable(STOCK_CONFIRM_QUEUE)
                .withArgument("x-dead-letter-exchange", STOCK_CONFIRM_DLX)
                .build();
    }

    @Bean
    public FanoutExchange stockConfirmDlx() {
        return new FanoutExchange(STOCK_CONFIRM_DLX);
    }

    @Bean
    public Queue stockConfirmDlq() {
        return QueueBuilder.durable(STOCK_CONFIRM_DLQ).build();
    }

    @Bean
    public Binding bindStockConfirmQueue() {
        return BindingBuilder.bind(stockConfirmQueue()).to(stockConfirmExchange());
    }

    @Bean
    public Binding bindStockConfirmDlq() {
        return BindingBuilder.bind(stockConfirmDlq()).to(stockConfirmDlx());
    }

    @Bean
    public FanoutExchange stockReleaseExchange() {
        return new FanoutExchange(STOCK_RELEASE_EXCHANGE);
    }

    @Bean
    public Queue stockReleaseQueue() {
        return QueueBuilder.durable(STOCK_RELEASE_QUEUE)
                .withArgument("x-dead-letter-exchange", STOCK_RELEASE_DLX)
                .build();
    }

    @Bean
    public FanoutExchange stockReleaseDlx() {
        return new FanoutExchange(STOCK_RELEASE_DLX);
    }

    @Bean
    public Queue stockReleaseDlq() {
        return QueueBuilder.durable(STOCK_RELEASE_DLQ).build();
    }

    @Bean
    public Binding bindStockReleaseQueue() {
        return BindingBuilder.bind(stockReleaseQueue()).to(stockReleaseExchange());
    }

    @Bean
    public Binding bindStockReleaseDlq() {
        return BindingBuilder.bind(stockReleaseDlq()).to(stockReleaseDlx());
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new JacksonJsonMessageConverter();
    }
}
