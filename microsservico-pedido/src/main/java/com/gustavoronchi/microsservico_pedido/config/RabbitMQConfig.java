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
    public static final String STOCK_CONFIRM_EXCHANGE = "stock.confirm";
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
    public FanoutExchange stockConfirmExchange() {
        return new FanoutExchange(STOCK_CONFIRM_EXCHANGE);
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
