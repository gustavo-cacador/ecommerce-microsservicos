package com.gustavoronchi.microsservico_pagamento.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
public class RabbitMQConfig {

    public static final String STOCK_RESERVED_EXCHANGE = "stock.reserved";
    public static final String PAYMENT_STOCK_RESERVED_QUEUE = "payment.stock.reserved.queue";
    public static final String PAYMENT_STOCK_RESERVED_DLX = "payment.stock.reserved.dlx";
    public static final String PAYMENT_STOCK_RESERVED_DLQ = "payment.stock.reserved.dlq";
    public static final String PAYMENT_APPROVED_EXCHANGE = "payment.approved";
    public static final String ORDER_PAYMENT_APPROVED_QUEUE = "order.payment.approved.queue";
    public static final String ORDER_PAYMENT_APPROVED_DLX = "order.payment.approved.dlx";
    public static final String ORDER_PAYMENT_APPROVED_DLQ = "order.payment.approved.dlq";
    public static final String STOCK_PAYMENT_APPROVED_QUEUE = "stock.payment.approved.queue";
    public static final String STOCK_PAYMENT_APPROVED_DLX = "stock.payment.approved.dlx";
    public static final String STOCK_PAYMENT_APPROVED_DLQ = "stock.payment.approved.dlq";
    public static final String PAYMENT_REFUSED_EXCHANGE = "payment.refused";
    public static final String ORDER_PAYMENT_REFUSED_QUEUE = "order.payment.refused.queue";
    public static final String ORDER_PAYMENT_REFUSED_DLX = "order.payment.refused.dlx";
    public static final String ORDER_PAYMENT_REFUSED_DLQ = "order.payment.refused.dlq";
    public static final String STOCK_PAYMENT_REFUSED_QUEUE = "stock.payment.refused.queue";
    public static final String STOCK_PAYMENT_REFUSED_DLX = "stock.payment.refused.dlx";
    public static final String STOCK_PAYMENT_REFUSED_DLQ = "stock.payment.refused.dlq";

    @Bean
    public FanoutExchange stockReservedExchange() {
        return new FanoutExchange(STOCK_RESERVED_EXCHANGE);
    }

    @Bean
    public Queue paymentStockReservedQueue() {
        return QueueBuilder.durable(PAYMENT_STOCK_RESERVED_QUEUE)
                .withArgument("x-dead-letter-exchange", PAYMENT_STOCK_RESERVED_DLX).build();
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
    public FanoutExchange paymentApprovedExchange() {
        return new FanoutExchange(PAYMENT_APPROVED_EXCHANGE);
    }

    @Bean
    public Queue orderPaymentApprovedQueue() {
        return QueueBuilder.durable(ORDER_PAYMENT_APPROVED_QUEUE)
                .withArgument("x-dead-letter-exchange", ORDER_PAYMENT_APPROVED_DLX).build();
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
    public Queue stockPaymentApprovedQueue() {
        return QueueBuilder.durable(STOCK_PAYMENT_APPROVED_QUEUE)
                .withArgument("x-dead-letter-exchange", STOCK_PAYMENT_APPROVED_DLX).build();
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
    public FanoutExchange paymentRefusedExchange() {
        return new FanoutExchange(PAYMENT_REFUSED_EXCHANGE);
    }

    @Bean
    public Queue orderPaymentRefusedQueue() {
        return QueueBuilder.durable(ORDER_PAYMENT_REFUSED_QUEUE)
                .withArgument("x-dead-letter-exchange", ORDER_PAYMENT_REFUSED_DLX).build();
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
    public Queue stockPaymentRefusedQueue() {
        return QueueBuilder.durable(STOCK_PAYMENT_REFUSED_QUEUE)
                .withArgument("x-dead-letter-exchange", STOCK_PAYMENT_REFUSED_DLX).build();
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
    public MessageConverter jsonMessageConverter() {
        return new JacksonJsonMessageConverter();
    }
}
