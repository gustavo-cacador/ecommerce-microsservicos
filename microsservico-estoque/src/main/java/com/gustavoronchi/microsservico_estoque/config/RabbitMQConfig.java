package com.gustavoronchi.microsservico_estoque.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    public static final String STOCK_CONFIRM_EXCHANGE = "stock.confirm";
    public static final String STOCK_CONFIRM_QUEUE = "stock.confirm.queue";
    public static final String STOCK_CONFIRM_DLQ = "stock.confirm.dlq";
    public static final String STOCK_CONFIRM_DLX = "stock.confirm.dlx";

    public static final String STOCK_RELEASE_EXCHANGE = "stock.release";
    public static final String STOCK_RELEASE_QUEUE = "stock.release.queue";
    public static final String STOCK_RELEASE_DLQ = "stock.release.dlq";
    public static final String STOCK_RELEASE_DLX = "stock.release.dlx";

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
