package com.lawground.job.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lawground.job.config.MockQueueConfiguration;
import com.rabbitmq.client.Channel;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "lawground.analysis.mock-enabled", havingValue = "true")
public class MockJobWorker {
    private final JobStore store;
    private final ObjectMapper mapper;
    private final String worker = "spring-mock-" + UUID.randomUUID();
    private final ScheduledExecutorService heartbeats =
            Executors.newSingleThreadScheduledExecutor(
                    r -> {
                        Thread t = new Thread(r, "mock-job-heartbeat");
                        t.setDaemon(true);
                        return t;
                    });

    public MockJobWorker(JobStore store, ObjectMapper mapper) {
        this.store = store;
        this.mapper = mapper;
    }

    @PreDestroy
    void close() {
        heartbeats.shutdownNow();
    }

    @RabbitListener(
            queues = MockQueueConfiguration.QUEUE,
            ackMode = "MANUAL",
            autoStartup = "${lawground.analysis.listener-enabled:true}")
    public void receive(Message message, Channel channel) throws IOException {
        long tag = message.getMessageProperties().getDeliveryTag();
        JobPublisher.Envelope envelope;
        try {
            if (message.getBody().length > 2048) throw new IllegalArgumentException();
            envelope = mapper.readValue(message.getBody(), JobPublisher.Envelope.class);
            if (envelope.schemaVersion() != 1
                    || envelope.jobId() == null
                    || envelope.requestId() == null) throw new IllegalArgumentException();
        } catch (IOException | IllegalArgumentException badMessage) {
            channel.basicReject(tag, false);
            return;
        }
        // Database exceptions propagate without ACK; the DB scanner recovers unfinished jobs.
        process(envelope.jobId());
        channel.basicAck(tag, false);
    }

    public void process(UUID jobId) {
        var claim = store.claim(jobId, worker);
        if (claim == null) return;
        var heartbeat =
                heartbeats.scheduleAtFixedRate(
                        () -> store.heartbeat(claim), 30, 30, TimeUnit.SECONDS);
        try {
            for (var stage :
                    new JobStore.Stage[] {
                        JobStore.Stage.PREPARE, JobStore.Stage.SEARCH, JobStore.Stage.VERIFY
                    }) {
                var start = store.beginStage(claim, stage);
                if (start == JobStore.Start.LOST) return;
                if (start == JobStore.Start.STARTED && !store.finishStage(claim, stage)) return;
            }
            if (store.beginStage(claim, JobStore.Stage.PERSIST) == JobStore.Start.STARTED)
                store.completeMock(claim);
        } catch (org.springframework.dao.DataAccessException infrastructure) {
            // Leave unacknowledged; a DB outage must not be converted to a legal HOLD verdict.
            throw infrastructure;
        } catch (RuntimeException failure) {
            store.retry(claim, "WORKER_FAILURE", null);
        } finally {
            heartbeat.cancel(false);
        }
    }
}
