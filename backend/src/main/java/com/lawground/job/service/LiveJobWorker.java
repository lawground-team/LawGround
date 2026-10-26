package com.lawground.job.service;

import static com.lawground.job.service.LiveWorkerFailure.Code.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lawground.integration.ai.client.AiClientException;
import com.lawground.job.config.LiveQueueConfiguration;
import com.lawground.law.search.LiveRetrievalService;
import com.lawground.law.search.LiveSearchFailure;
import com.rabbitmq.client.Channel;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/** Owns technical retries. Retrieval is never a substitute for a legal verifier. */
@Component
@ConditionalOnProperty(prefix = "lawground.analysis.live", name = "enabled", havingValue = "true")
public class LiveJobWorker {
    private final JobStore store;
    private final LiveRunPlan plan;
    private final LiveRetrievalService retrieval;
    private final ObjectMapper mapper;
    private final String worker = "spring-live-" + UUID.randomUUID();
    private final ScheduledExecutorService heartbeats =
            Executors.newSingleThreadScheduledExecutor(
                    r -> {
                        Thread thread = new Thread(r, "live-job-heartbeat");
                        thread.setDaemon(true);
                        return thread;
                    });

    public LiveJobWorker(
            JobStore store, LiveRunPlan plan, LiveRetrievalService retrieval, ObjectMapper mapper) {
        this.store = store;
        this.plan = plan;
        this.retrieval = retrieval;
        this.mapper = mapper;
    }

    @PreDestroy
    void close() {
        heartbeats.shutdownNow();
    }

    @RabbitListener(
            queues = LiveQueueConfiguration.QUEUE,
            ackMode = "MANUAL",
            autoStartup = "${lawground.analysis.listener-enabled:true}")
    public void receive(Message message, Channel channel) throws IOException {
        long tag = message.getMessageProperties().getDeliveryTag();
        JobPublisher.Envelope envelope;
        try {
            if (message.getBody().length > 2048) throw new IllegalArgumentException();
            var tree = mapper.readTree(message.getBody());
            if (tree == null
                    || !tree.isObject()
                    || tree.size() != 3
                    || !tree.path("schemaVersion").isIntegralNumber()
                    || !tree.path("schemaVersion").canConvertToInt()
                    || tree.path("schemaVersion").intValue() != 1)
                throw new IllegalArgumentException();
            envelope = mapper.treeToValue(tree, JobPublisher.Envelope.class);
            if (envelope.jobId() == null || envelope.requestId() == null)
                throw new IllegalArgumentException();
        } catch (IOException | IllegalArgumentException invalid) {
            channel.basicReject(tag, false);
            return;
        }
        // DB failures propagate without ACK. Durable job/lease recovery remains authoritative.
        process(envelope.jobId());
        channel.basicAck(tag, false);
    }

    public void process(UUID id) {
        var claim = store.claim(id, worker, "LIVE");
        if (claim == null) return;
        var heartbeat =
                heartbeats.scheduleAtFixedRate(
                        () -> store.heartbeat(claim), 30, 30, TimeUnit.SECONDS);
        try {
            var work = store.liveWork(claim);
            if (work == null) return;
            if (!LiveRunPlan.PIPELINE.equals(work.pipelineVersion()))
                throw new LiveWorkerFailure(PIPELINE_VERSION_MISMATCH);
            var config = plan.read(work.config());
            var query = plan.query(config, claim.snapshot());
            plan.requireCorpus(config, claim.snapshot());
            var start = store.beginStage(claim, JobStore.Stage.PREPARE);
            if (start == JobStore.Start.LOST
                    || (start == JobStore.Start.STARTED
                            && !store.finishStage(claim, JobStore.Stage.PREPARE))) return;
            start = store.beginStage(claim, JobStore.Stage.SEARCH);
            if (start == JobStore.Start.LOST) return;
            if (start == JobStore.Start.STARTED) {
                var result = retrieval.search(query); // No open DB transaction during model calls.
                validate(result, config);
                if (!store.recordLiveRetrieval(claim, result)) return;
            } else {
                var saved = work.checkpoint().path("retrieval");
                if (!saved.isObject()
                        || saved.size() != 4
                        || !saved.path("schemaVersion").isInt()
                        || saved.path("schemaVersion").intValue() != 1
                        || !"RETRIEVAL_ONLY".equals(saved.path("mode").asText())
                        || !saved.path("verificationPerformed").isBoolean()
                        || saved.path("verificationPerformed").booleanValue())
                    throw new LiveWorkerFailure(CHECKPOINT_INVALID);
                try {
                    validate(
                            mapper.treeToValue(
                                    saved.path("result"), LiveRetrievalService.Result.class),
                            config);
                } catch (com.fasterxml.jackson.core.JsonProcessingException
                        | IllegalArgumentException invalid) {
                    throw new LiveWorkerFailure(CHECKPOINT_INVALID);
                }
            }
            plan.requireCorpus(config, claim.snapshot());
            var verify = store.beginStage(claim, JobStore.Stage.VERIFY);
            if (verify == JobStore.Start.SKIP) throw new LiveWorkerFailure(CHECKPOINT_INVALID);
            if (verify == JobStore.Start.STARTED) store.failLive(claim, VERIFIER_NOT_CONFIGURED);
        } catch (DataAccessException infrastructure) {
            throw infrastructure;
        } catch (AiClientException failure) {
            if (failure.retryable()) store.retry(claim, "AI_UNAVAILABLE", failure.retryAfter());
            else
                store.failLive(
                        claim,
                        switch (failure.kind()) {
                            case CONFIGURATION -> AI_CONFIGURATION_INVALID;
                            case INPUT_INVALID -> AI_INPUT_INVALID;
                            case VERSION_MISMATCH -> MODEL_REVISION_MISMATCH;
                            case NOT_READY -> MODEL_NOT_READY;
                            default ->
                                    "MODEL_NOT_READY".equals(failure.code())
                                            ? MODEL_NOT_READY
                                            : AI_CONTRACT_INVALID;
                        });
        } catch (LiveWorkerFailure failure) {
            store.failLive(claim, failure.code());
        } catch (LiveSearchFailure failure) {
            store.failLive(
                    claim,
                    switch (failure.code()) {
                        case CORPUS_CHANGED -> CORPUS_CHANGED;
                        case INVALID_SOURCE, SOURCE_CHANGED -> INVALID_SOURCE;
                        default -> INVALID_INDEX;
                    });
        } catch (RuntimeException failure) {
            store.retry(claim, "WORKER_FAILURE", null);
        } finally {
            heartbeat.cancel(false);
        }
    }

    private static void validate(LiveRetrievalService.Result result, LiveRunPlan.Config config) {
        if (result == null
                || result.status() == null
                || !Objects.equals(result.manifestHash(), config.corpusManifestHash())
                || result.lawVersionIds() == null
                || !result.lawVersionIds().equals(config.lawVersionIds())
                || !Objects.equals(result.profile(), config.profile())
                || result.hits() == null
                || result.hits().size() > config.topK()
                || result.fusedCandidateCount() < 0
                || result.fusedCandidateCount() > 60
                || result.rerankedCandidateCount() < 0
                || result.rerankedCandidateCount() > config.candidateLimit()
                || result.rerankedCandidateCount() > result.fusedCandidateCount())
            throw new LiveWorkerFailure(CHECKPOINT_INVALID);
        if (result.status() == LiveRetrievalService.Status.INDEX_NOT_READY)
            throw new LiveWorkerFailure(INDEX_NOT_READY);
        if (result.status() == LiveRetrievalService.Status.LAW_VERSION_UNAVAILABLE)
            throw new LiveWorkerFailure(LAW_VERSION_UNAVAILABLE);
        if (result.status() == LiveRetrievalService.Status.RETRIEVED) {
            if (!Objects.equals(result.rerankerRevision(), config.rerankerModelRevision())
                    || result.hits().isEmpty()
                    || result.hits().size()
                            != Math.min(config.topK(), result.rerankedCandidateCount()))
                throw new LiveWorkerFailure(CHECKPOINT_INVALID);
        } else if (!result.hits().isEmpty()
                || result.rerankedCandidateCount() != 0
                || result.fusedCandidateCount() != 0
                || result.rerankerRevision() != null)
            throw new LiveWorkerFailure(CHECKPOINT_INVALID);
        var ids = new HashSet<UUID>();
        for (var hit : result.hits()) {
            if (hit == null
                    || hit.chunkId() == null
                    || !ids.add(hit.chunkId())
                    || hit.articleId() == null
                    || !config.lawVersionIds().contains(hit.lawVersionId())
                    || hit.sourceText() == null
                    || hit.sourceText().isBlank()
                    || !Double.isFinite(hit.rrfScore())
                    || hit.rrfScore() <= 0
                    || !Double.isFinite(hit.rerankScore()))
                throw new LiveWorkerFailure(CHECKPOINT_INVALID);
        }
    }
}
