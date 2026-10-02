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
    public MessageConverter jsonMessageConverter() {
        return new JacksonJsonMessageConverter();
    }
}
