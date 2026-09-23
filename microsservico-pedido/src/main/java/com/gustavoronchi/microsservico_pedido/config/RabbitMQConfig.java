package com.gustavoronchi.microsservico_pedido.config;

import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    public static final String STOCK_CONFIRM_EXCHANGE = "stock.confirm";
    public static final String STOCK_RELEASE_EXCHANGE = "stock.release";

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
