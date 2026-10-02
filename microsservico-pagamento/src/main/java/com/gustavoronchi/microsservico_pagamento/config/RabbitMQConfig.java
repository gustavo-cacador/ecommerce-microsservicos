package com.gustavoronchi.microsservico_pagamento.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    public static final String STOCK_RESERVED_EXCHANGE = "stock.reserved";
    public static final String PAYMENT_STOCK_RESERVED_QUEUE = "payment.stock.reserved.queue";
    public static final String PAYMENT_STOCK_RESERVED_DLX = "payment.stock.reserved.dlx";
    public static final String PAYMENT_STOCK_RESERVED_DLQ = "payment.stock.reserved.dlq";

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
    public MessageConverter jsonMessageConverter() {
        return new JacksonJsonMessageConverter();
    }
}
