package com.gustavoronchi.microsservico_estoque.messaging;

import com.gustavoronchi.microsservico_estoque.domain.entities.OutboxEvent;
import com.gustavoronchi.microsservico_estoque.domain.repository.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxEventRepository outboxRepository;
    private final RabbitTemplate rabbitTemplate;
    private final long confirmTimeoutMs;

    public OutboxRelay(OutboxEventRepository outboxRepository, RabbitTemplate rabbitTemplate, @Value("${outbox.relay.confirm-timeout-ms:5000}") long confirmTimeoutMs) {
        this.outboxRepository = outboxRepository;
        this.rabbitTemplate = rabbitTemplate;
        this.confirmTimeoutMs = confirmTimeoutMs;
    }

    @Scheduled(fixedDelayString = "${outbox.relay.delay-ms:3000}")
    public void publishPending() {
        for (OutboxEvent event : outboxRepository.findByPublishedAtIsNullOrderByOccurredAtAsc(PageRequest.of(0, 20))) {
            try {
                MessageProperties properties = new MessageProperties();
                properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
                properties.setContentEncoding(StandardCharsets.UTF_8.name());
                properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                properties.setMessageId(event.getEventId().toString());

                // O payload já é JSON; enviá-lo como String pelo conversor serializaria o JSON novamente.
                Message message = new Message(event.getPayload().getBytes(StandardCharsets.UTF_8), properties);
                // Correlação única por tentativa; eventId e payload permanecem iguais nos reenvios.
                CorrelationData correlation = new CorrelationData();
                rabbitTemplate.send(event.getExchange(), event.getRoutingKey(), message, correlation);
                CorrelationData.Confirm confirm = correlation.getFuture().get(confirmTimeoutMs, TimeUnit.MILLISECONDS);

                if (!confirm.ack() || correlation.getReturned() != null) {
                    log.warn("Publicação pendente: eventId={}, orderId={}, ack={}, motivo={}, devolução={}",
                            event.getEventId(), event.getOrderId(), confirm.ack(), confirm.reason(), correlation.getReturned());
                    continue;
                }

                outboxRepository.markPublished(event.getEventId(), Instant.now());
                log.info("Evento publicado: eventId={}, orderId={}", event.getEventId(), event.getOrderId());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception ex) {
                log.warn("Falha ao publicar evento; permanece pendente: eventId={}, orderId={}",
                        event.getEventId(), event.getOrderId(), ex);
            }
        }
    }
}

