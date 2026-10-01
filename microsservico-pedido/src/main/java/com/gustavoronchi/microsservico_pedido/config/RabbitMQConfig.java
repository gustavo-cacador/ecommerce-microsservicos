package com.gustavoronchi.microsservico_pedido.config;

import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
public class RabbitMQConfig {

    public static final String ORDER_CREATED_EXCHANGE = "order.created";
    public static final String STOCK_CONFIRM_EXCHANGE = "stock.confirm";
    public static final String STOCK_RELEASE_EXCHANGE = "stock.release";

    @Bean
    public FanoutExchange orderCreatedExchange() {
        return new FanoutExchange(ORDER_CREATED_EXCHANGE);
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
