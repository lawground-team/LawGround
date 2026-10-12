package com.lawground.job.service;

import com.lawground.global.config.JsonData;
import com.lawground.job.config.MockQueueConfiguration;
import com.lawground.trace.service.AnalysisService;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@ConditionalOnProperty(name = "lawground.analysis.mock-enabled", havingValue = "true")
public class JobPublisher {
    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(JobPublisher.class);

    public record Envelope(int schemaVersion, UUID jobId, UUID requestId) {}

    private final RabbitTemplate rabbit;
    private final JobStore store;
    private final JsonData json;

    public JobPublisher(RabbitTemplate rabbit, JobStore store, JsonData json) {
        this.rabbit = rabbit;
        this.store = store;
        this.json = json;
    }

    @TransactionalEventListener
    public void submitted(AnalysisService.Submitted event) {
        try {
            publish(event.jobId());
        } catch (org.springframework.dao.DataAccessException error) {
            // The admission transaction already committed; the scanner can retry from PENDING.
            log.warn(
                    "Publication bookkeeping unavailable type={}",
                    error.getClass().getSimpleName());
        }
    }

    public void publish(UUID id) {
        int attempt = store.publicationAttempt(id);
        if (attempt == 0) return;
        var properties = new MessageProperties();
        properties.setContentType("application/json");
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        var data = new CorrelationData(UUID.randomUUID().toString());
        try {
            rabbit.send(
                    MockQueueConfiguration.EXCHANGE,
                    MockQueueConfiguration.KEY,
                    new Message(
                            json.write(new Envelope(1, id, UUID.randomUUID()))
                                    .getBytes(StandardCharsets.UTF_8),
                            properties),
                    data);
            data.getFuture()
                    .orTimeout(5, TimeUnit.SECONDS)
                    .whenComplete(
                            (confirm, error) ->
                                    store.published(
                                            id,
                                            attempt,
                                            error == null
                                                    && confirm.isAck()
                                                    && data.getReturned() == null));
        } catch (org.springframework.amqp.AmqpException error) {
            store.published(id, attempt, false);
        }
    }

    @Scheduled(fixedDelayString = "${lawground.analysis.recovery-delay-ms:5000}")
    public void recover() {
        for (UUID id : store.recoverable()) if (!store.expire(id)) publish(id);
    }
}
