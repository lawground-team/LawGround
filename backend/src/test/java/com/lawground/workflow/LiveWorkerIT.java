package com.lawground.workflow;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lawground.integration.ai.dto.AiDtos.*;
import com.lawground.job.config.LiveQueueConfiguration;
import com.lawground.job.service.*;
import com.lawground.law.dto.LawImport;
import com.lawground.law.search.*;
import com.lawground.law.service.LawImportService;
import com.rabbitmq.client.Channel;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.InetSocketAddress;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.*;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;

/** Real PG/queue/HTTP, synthetic inference only. Does not certify legal quality or real weights. */
@SpringBootTest(
        properties = {
            "lawground.analysis.live.enabled=true",
            "lawground.analysis.mock-enabled=false",
            "lawground.analysis.listener-enabled=false",
            "lawground.analysis.recovery-delay-ms=3600000"
        })
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class LiveWorkerIT {
    static final UUID LAW = UUID.randomUUID();
    static final String MODEL = "a".repeat(40),
            RERANK = "b".repeat(40),
            TOKENIZER = "kiwipiepy-0.24.0+rules-1";
    static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    static final AtomicInteger calls = new AtomicInteger();
    static final List<String> queries = new CopyOnWriteArrayList<>(),
            violations = new CopyOnWriteArrayList<>();
    static volatile int failureStatus;
    static volatile String retryHeader;
    static volatile boolean foreignId;
    static volatile Runnable onRerank = () -> {};
    static final HttpServer HTTP = server();

    @Container @ServiceConnection
    static final PostgreSQLContainer<?> pg =
            new PostgreSQLContainer<>(
                    DockerImageName.parse("pgvector/pgvector:0.8.2-pg16")
                            .asCompatibleSubstituteFor("postgres"));

    @Container @ServiceConnection
    static final RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:4.2.9-management");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("lawground.analysis.live.law-ids", LAW::toString);
        r.add(
                "lawground.ai.base-url",
                () -> "http://127.0.0.1:" + HTTP.getAddress().getPort() + "/internal/v1");
        r.add("lawground.ai.api-key", () -> "synthetic-worker-test-token");
        r.add("lawground.ai.embedding-model-revision", () -> MODEL);
        r.add("lawground.ai.tokenizer-version", () -> TOKENIZER);
        r.add("lawground.ai.reranker-model-revision", () -> RERANK);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JobStore jobs;
    @Autowired JobPublisher publisher;
    @Autowired LiveJobWorker worker;
    @Autowired LiveRunPlan plan;
    @Autowired LiveRetrievalService retrieval;
    @Autowired LiveLawIndexService indexer;
    @Autowired LawImportService imports;
    @Autowired RabbitTemplate template;
    @Autowired RabbitListenerEndpointRegistry listeners;
    UUID owner, other, chunk, version;

    @BeforeEach
    void setup() {
        failureStatus = 0;
        retryHeader = null;
        foreignId = false;
        onRerank = () -> {};
        owner = UUID.randomUUID();
        other = UUID.randomUUID();
        for (var id : List.of(owner, other))
            jdbc.update(
                    "INSERT INTO members(id,display_name,status) VALUES (?,'Synthetic worker member','ACTIVE')",
                    id);
        jdbc.update(
                "INSERT INTO laws(id,source_provider,source_law_id,title) VALUES (?,'LAW_GO_KR','SYNTHETIC-WORKER','SYNTHETIC') ON CONFLICT DO NOTHING",
                LAW);
        var data =
                imports.importVersion(
                        new LawImport(
                                "LAW_GO_KR",
                                "SYNTHETIC-WORKER",
                                "v1",
                                "SYNTHETIC",
                                LocalDate.of(2024, 1, 1),
                                null,
                                "VERIFIED",
                                "https://www.law.go.kr/TEST-ONLY",
                                Map.of("synthetic", true),
                                List.of(
                                        new LawImport.Article(
                                                "a1",
                                                "TEST",
                                                "alpha 합성 원문",
                                                List.of(
                                                        new LawImport.Span(
                                                                0, 11, "alpha 합성 원문"))))));
        version = data.versionId();
        chunk = data.chunkIds().getFirst();
        indexer.indexChunk(chunk);
        calls.set(0);
        queries.clear();
        violations.clear();
        new RabbitAdmin(template.getConnectionFactory())
                .purgeQueue(LiveQueueConfiguration.QUEUE, false);
    }

    @AfterEach
    void cleanup() {
        failureStatus = 0;
        foreignId = false;
        onRerank = () -> {};
        assertThat(violations).isEmpty();
    }

    @AfterAll
    static void close() {
        HTTP.stop(0);
    }

    ObjectNode input(String stem) {
        var node =
                JSON.createObjectNode()
                        .put("subject", "BROKER_LAW")
                        .put("stem", stem)
                        .put("questionType", "SELECT_INCORRECT")
                        .put("providedAnswerNumber", 2)
                        .put("referenceDate", "2025-10-25");
        var choices = node.putArray("choices");
        for (int i = 1; i <= 5; i++) choices.addObject().put("number", i).put("text", "합성 선지 " + i);
        return node;
    }

    JsonNode request(MockHttpServletRequestBuilder request, int expected) throws Exception {
        var response =
                mvc.perform(request.with(user(owner.toString())).with(csrf()))
                        .andExpect(status().is(expected))
                        .andReturn()
                        .getResponse();
        var body =
                response.getContentAsByteArray().length == 0
                        ? null
                        : JSON.readTree(response.getContentAsByteArray());
        if (body != null) {
            var built = request.buildRequest(new org.springframework.mock.web.MockServletContext());
            String uri = built.getRequestURI();
            String schema =
                    expected >= 400
                            ? "Error"
                            : uri.contains("/jobs/")
                                    ? "Job"
                                    : uri.endsWith("/cancel")
                                            ? "CancelAccepted"
                                            : uri.endsWith("/trace-runs")
                                                    ? "TraceAccepted"
                                                    : uri.contains("/trace-runs/")
                                                            ? "TraceRun"
                                                            : "Question";
            var folder = java.nio.file.Path.of("build", "contract-responses");
            java.nio.file.Files.createDirectories(folder);
            JSON.writeValue(
                    folder.resolve(UUID.randomUUID() + ".json").toFile(),
                    Map.of("schema", schema, "body", body));
        }
        return body;
    }

    UUID question() throws Exception {
        return UUID.fromString(
                request(
                                post("/api/v1/questions")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                JSON.writeValueAsBytes(
                                                        input(
                                                                "합성 문제: 옳지 않은 것은? "
                                                                        + UUID.randomUUID()))),
                                201)
                        .path("id")
                        .asText());
    }

    JsonNode trace(UUID q, UUID key) throws Exception {
        return request(
                post("/api/v1/questions/" + q + "/trace-runs")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"questionRevision\":1}"),
                202);
    }

    UUID job(JsonNode trace) {
        return UUID.fromString(trace.path("jobId").asText());
    }

    UUID run(JsonNode trace) {
        return UUID.fromString(trace.path("runId").asText());
    }

    String jobStatus(UUID id) {
        return jdbc.queryForObject(
                "SELECT status FROM processing_jobs WHERE id=?", String.class, id);
    }

    JsonNode checkpoint(JsonNode trace) throws Exception {
        return JSON.readTree(
                jdbc.queryForObject(
                        "SELECT checkpoint::text FROM trace_runs WHERE id=?",
                        String.class,
                        run(trace)));
    }

    void failed(JsonNode trace, String code) {
        assertThat(jobStatus(job(trace))).isEqualTo("FAILED");
        assertThat(
                        jdbc.queryForMap(
                                "SELECT status,verdict,reason_code FROM trace_runs WHERE id=?",
                                run(trace)))
                .containsEntry("status", "FAILED")
                .containsEntry("verdict", null)
                .containsEntry("reason_code", code);
    }

    @Test
    void queueEnvelopeRealListenerSnapshotAndNoFakeVerdict() throws Exception {
        UUID q = question(), key = UUID.randomUUID();
        var trace = trace(q, key);
        UUID id = job(trace);
        Message message = template.receive(LiveQueueConfiguration.QUEUE, 5000);
        assertThat(message).isNotNull();
        assertThat(message.getMessageProperties().getReceivedDeliveryMode())
                .isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(JSON.readTree(message.getBody()).size()).isEqualTo(3);
        assertThat(new String(message.getBody(), java.nio.charset.StandardCharsets.UTF_8))
                .doesNotContain("선지", "stem", "token");
        // Same idempotency key retains the admitted run even after the stored question changes.
        request(
                put("/api/v1/questions/" + q)
                        .header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsBytes(input("edited question"))),
                200);
        assertThat(trace(q, key).path("runId")).isEqualTo(trace.path("runId"));
        template.send(
                com.lawground.job.config.MockQueueConfiguration.EXCHANGE,
                LiveQueueConfiguration.KEY,
                message);
        listeners.start();
        try {
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (!"FAILED".equals(jobStatus(id)) && System.nanoTime() < end) Thread.sleep(20);
        } finally {
            listeners.stop();
        }
        failed(trace, "VERIFIER_NOT_CONFIGURED");
        var saved = checkpoint(trace);
        assertThat(saved.path("SEARCH").asBoolean()).isTrue();
        assertThat(saved.at("/retrieval/verificationPerformed").booleanValue()).isFalse();
        assertThat(saved.at("/retrieval/result/hits/0/chunkId").asText())
                .isEqualTo(chunk.toString());
        assertThat(queries)
                .allSatisfy(
                        text -> {
                            assertThat(text)
                                    .contains(
                                            "SELECT_INCORRECT",
                                            "옳지 않은",
                                            "choice[5]: 합성 선지 5",
                                            "providedAnswerNumber: 2")
                                    .doesNotContain("edited question");
                        });
        assertThat(calls.get()).isEqualTo(3);
        worker.process(id);
        assertThat(calls.get()).isEqualTo(3);
        var state = request(get("/api/v1/trace-runs/" + run(trace)), 200);
        assertThat(state.path("executionMode").asText()).isEqualTo("LIVE");
        assertThat(state.path("versions").size()).isEqualTo(6);
        assertThat(saved.at("/retrieval/result/rerankerRevision").asText()).isEqualTo(RERANK);
        assertThat(request(get("/api/v1/jobs/" + id), 200).path("errorCode").asText())
                .isEqualTo("VERIFIER_NOT_CONFIGURED");
        request(get("/api/v1/trace-runs/" + run(trace) + "/result"), 409);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM evidence_results WHERE run_id=?",
                                Integer.class,
                                run(trace)))
                .isZero();
    }

    @Test
    void temporaryUpstreamRetriesAreBoundedAndNotImmediatelyReclaimed() throws Exception {
        var trace = trace(question(), UUID.randomUUID());
        var id = job(trace);
        failureStatus = 502;
        for (int attempt = 1; attempt <= 3; attempt++) {
            worker.process(id);
            if (attempt < 3) {
                assertThat(jobStatus(id)).isEqualTo("RETRY_WAIT");
                assertThat(jobs.claim(id, "early", "LIVE")).isNull();
                assertThat(
                                jdbc.queryForObject(
                                        "SELECT extract(epoch from next_attempt_at-updated_at) FROM processing_jobs WHERE id=?",
                                        Double.class,
                                        id))
                        // updated_at is sampled just after next-at calculation; allow DB/clock
                        // latency.
                        .isBetween(attempt == 1 ? 4.9 : 19.9, attempt == 1 ? 6.01 : 21.01);
                jdbc.update(
                        "UPDATE processing_jobs SET next_attempt_at=now()-interval '1 second' WHERE id=?",
                        id);
            }
        }
        failed(trace, "AI_UNAVAILABLE");
        assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    void modelOffAndForeignCandidateFailWithoutRetries() throws Exception {
        var off = trace(question(), UUID.randomUUID());
        failureStatus = 503;
        worker.process(job(off));
        failed(off, "MODEL_NOT_READY");
        assertThat(calls.get()).isEqualTo(1);
        failureStatus = 0;
        foreignId = true;
        var bad = trace(question(), UUID.randomUUID());
        worker.process(job(bad));
        failed(bad, "AI_CONTRACT_INVALID");
        assertThat(checkpoint(bad).has("retrieval")).isFalse();
    }

    @Test
    void respectsRetryAfterAndOverallDeadline() throws Exception {
        failureStatus = 502;
        retryHeader = "30";
        var delayed = trace(question(), UUID.randomUUID());
        worker.process(job(delayed));
        assertThat(jobStatus(job(delayed))).isEqualTo("RETRY_WAIT");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT extract(epoch from next_attempt_at-updated_at) FROM processing_jobs WHERE id=?",
                                Double.class,
                                job(delayed)))
                .isBetween(29.0, 31.0);
        retryHeader = "1200";
        var late = trace(question(), UUID.randomUUID());
        worker.process(job(late));
        failed(late, "DEADLINE_EXCEEDED");
    }

    @Test
    void rejectsUnpreparedCorpusBeforeAdmissionAndCorruptResumeBeforeInference() throws Exception {
        UUID q = question();
        var oldDate = input("합성 과거 기준일 " + UUID.randomUUID()).put("referenceDate", "2020-01-01");
        request(
                put("/api/v1/questions/" + q)
                        .header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsBytes(oldDate)),
                200);
        request(
                post("/api/v1/questions/" + q + "/trace-runs")
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"questionRevision\":2}"),
                503);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM trace_runs WHERE question_id=?",
                                Integer.class,
                                q))
                .isZero();
        var broken = trace(question(), UUID.randomUUID());
        jdbc.update(
                "UPDATE trace_runs SET checkpoint='{\"SEARCH\":true}'::jsonb WHERE id=?",
                run(broken));
        worker.process(job(broken));
        failed(broken, "CHECKPOINT_INVALID");
        assertThat(calls.get()).isZero();
    }

    @Test
    void savedSearchResumesAfterLeaseLossWithoutRepeatingInference() throws Exception {
        var trace = trace(question(), UUID.randomUUID());
        var id = job(trace);
        var claim = jobs.claim(id, "crashed-worker", "LIVE");
        var work = jobs.liveWork(claim);
        var config = plan.read(work.config());
        jobs.beginStage(claim, JobStore.Stage.PREPARE);
        jobs.finishStage(claim, JobStore.Stage.PREPARE);
        jobs.beginStage(claim, JobStore.Stage.SEARCH);
        assertThat(
                        jobs.recordLiveRetrieval(
                                claim, retrieval.search(plan.query(config, claim.snapshot()))))
                .isTrue();
        int before = calls.get();
        jdbc.update(
                "UPDATE processing_jobs SET lease_until=now()-interval '1 second' WHERE id=?", id);
        worker.process(id);
        failed(trace, "VERIFIER_NOT_CONFIGURED");
        assertThat(calls.get()).isEqualTo(before);
        assertThat(jobs.failLive(claim, LiveWorkerFailure.Code.AI_CONTRACT_INVALID)).isFalse();
        assertThat(jobs.recordLiveRetrieval(claim, null)).isFalse();
    }

    @Test
    void corruptSavedHitFailsWithoutRetryingOrRepeatingModels() throws Exception {
        var trace = trace(question(), UUID.randomUUID());
        var id = job(trace);
        var claim = jobs.claim(id, "crashed-worker", "LIVE");
        var config = plan.read(jobs.liveWork(claim).config());
        jobs.beginStage(claim, JobStore.Stage.PREPARE);
        jobs.finishStage(claim, JobStore.Stage.PREPARE);
        jobs.beginStage(claim, JobStore.Stage.SEARCH);
        jobs.recordLiveRetrieval(claim, retrieval.search(plan.query(config, claim.snapshot())));
        int before = calls.get();
        jdbc.update(
                "UPDATE trace_runs SET checkpoint=jsonb_set(checkpoint,'{retrieval,result,hits}','[null]'::jsonb) WHERE id=?",
                run(trace));
        jdbc.update(
                "UPDATE processing_jobs SET lease_until=now()-interval '1 second' WHERE id=?", id);
        worker.process(id);
        failed(trace, "CHECKPOINT_INVALID");
        assertThat(calls.get()).isEqualTo(before);
    }

    @Test
    void cancelDeleteAndRevokeDuringInferenceBlockLateWrites() throws Exception {
        for (String action : List.of("cancel", "delete", "revoke")) {
            UUID q = question();
            var trace = trace(q, UUID.randomUUID());
            onRerank =
                    () -> {
                        try {
                            if (action.equals("cancel"))
                                request(post("/api/v1/trace-runs/" + run(trace) + "/cancel"), 202);
                            else if (action.equals("delete"))
                                request(
                                        delete("/api/v1/questions/" + q)
                                                .header("If-Match", "\"1\""),
                                        204);
                            else
                                jdbc.update(
                                        "UPDATE members SET status='DEACTIVATED' WHERE id=?",
                                        owner);
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    };
            worker.process(job(trace));
            assertThat(jobStatus(job(trace)))
                    .isEqualTo(action.equals("revoke") ? "FAILED" : "CANCELLED");
            assertThat(checkpoint(trace).has("retrieval")).isFalse();
        }
    }

    @Test
    void corruptPlanAndChangedCorpusFailBeforeModels() throws Exception {
        var pin = trace(question(), UUID.randomUUID());
        jdbc.update(
                "UPDATE trace_runs SET config_snapshot=jsonb_set(config_snapshot,'{embeddingModelRevision}',to_jsonb(?::text)) WHERE id=?",
                "c".repeat(40),
                run(pin));
        worker.process(job(pin));
        failed(pin, "MODEL_REVISION_MISMATCH");
        var corpus = trace(question(), UUID.randomUUID());
        jdbc.update(
                "UPDATE law_versions SET applicability_status='REVIEW_REQUIRED' WHERE id=?",
                version);
        try {
            worker.process(job(corpus));
            failed(corpus, "CORPUS_CHANGED");
        } finally {
            jdbc.update(
                    "UPDATE law_versions SET applicability_status='VERIFIED' WHERE id=?", version);
        }
        assertThat(calls.get()).isZero();
    }

    @Test
    void wrongModeCannotClaimPublishOrExpireOtherModes() throws Exception {
        var live = trace(question(), UUID.randomUUID());
        assertThat(jobs.claim(job(live), "mock-worker")).isNull();
        assertThat(jobs.completeMock(jobs.claim(job(live), "live-worker", "LIVE"))).isFalse();
        var mock = trace(question(), UUID.randomUUID());
        jdbc.update("UPDATE trace_runs SET execution_mode='MOCK' WHERE id=?", run(mock));
        int publications =
                jdbc.queryForObject(
                        "SELECT publish_attempts FROM processing_jobs WHERE id=?",
                        Integer.class,
                        job(mock));
        worker.process(job(mock));
        publisher.publish(job(mock));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT publish_attempts FROM processing_jobs WHERE id=?",
                                Integer.class,
                                job(mock)))
                .isEqualTo(publications);
        jdbc.update(
                "UPDATE processing_jobs SET deadline_at=now()-interval '1 second' WHERE id=?",
                job(mock));
        publisher.recover();
        assertThat(jobStatus(job(mock))).isNotEqualTo("FAILED");
        assertThat(calls.get()).isZero();
    }

    @Test
    void ownershipAndCorruptEnvelopeAndDbFailureAckBoundaries() throws Exception {
        var trace = trace(question(), UUID.randomUUID());
        for (String uri :
                List.of(
                        "/api/v1/jobs/" + job(trace),
                        "/api/v1/trace-runs/" + run(trace),
                        "/api/v1/trace-runs/" + run(trace) + "/result"))
            mvc.perform(get(uri).with(user(other.toString()))).andExpect(status().isNotFound());
        var channel = mock(Channel.class);
        var props = new MessageProperties();
        props.setDeliveryTag(12);
        worker.receive(
                new Message(
                        "{\"schemaVersion\":1}".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        props),
                channel);
        verify(channel).basicReject(12, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
        var broken = mock(JobStore.class);
        when(broken.claim(any(), any(), eq("LIVE")))
                .thenThrow(
                        new org.springframework.dao.DataAccessResourceFailureException(
                                "synthetic"));
        var instance = new LiveJobWorker(broken, plan, retrieval, JSON);
        var valid =
                new Message(
                        JSON.writeValueAsBytes(
                                new JobPublisher.Envelope(1, job(trace), UUID.randomUUID())),
                        props);
        assertThatThrownBy(() -> instance.receive(valid, channel))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
        assertThat(calls.get()).isZero();
    }

    private static HttpServer server() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(
                    Executors.newCachedThreadPool(
                            r -> {
                                var thread = new Thread(r);
                                thread.setDaemon(true);
                                return thread;
                            }));
            server.createContext(
                    "/internal/v1/",
                    exchange -> {
                        var request = JSON.readTree(exchange.getRequestBody());
                        String path =
                                exchange.getRequestURI()
                                        .getPath()
                                        .substring("/internal/v1/".length());
                        boolean readiness = path.equals("health/ready");
                        if (!readiness) calls.incrementAndGet();
                        if (!"synthetic-worker-test-token"
                                .equals(exchange.getRequestHeaders().getFirst("X-Internal-Token")))
                            violations.add("token");
                        if (!readiness
                                && !request.path("requestId")
                                        .asText()
                                        .equals(
                                                exchange.getRequestHeaders()
                                                        .getFirst("X-Request-Id")))
                            violations.add("correlation");
                        if (!"0.1.0-draft"
                                .equals(
                                        exchange.getRequestHeaders()
                                                .getFirst("X-Contract-Version")))
                            violations.add("contract");
                        Object response;
                        int status = failureStatus;
                        if (readiness) {
                            status = 0;
                            response =
                                    new ReadyOutput(
                                            ReadyStatus.READY,
                                            List.of(
                                                    new ModelState(
                                                            "tokenizer:kiwipiepy", TOKENIZER, true),
                                                    new ModelState(
                                                            "embed:BAAI/bge-m3", MODEL, true),
                                                    new ModelState(
                                                            "rerank:BAAI/bge-reranker-v2-m3",
                                                            RERANK,
                                                            true)));
                        } else if (status != 0)
                            response =
                                    Map.of(
                                            "code",
                                            status == 503
                                                    ? "MODEL_NOT_READY"
                                                    : "MODEL_UPSTREAM_ERROR",
                                            "message",
                                            "synthetic",
                                            "requestId",
                                            request.path("requestId").asText(),
                                            "timestamp",
                                            "2026-10-26T00:00:00Z",
                                            "retryable",
                                            status != 503);
                        else if (path.equals("tokenize")) {
                            var items = new ArrayList<TokenItem>();
                            for (var item : request.path("texts")) {
                                queries.add(item.path("text").asText());
                                items.add(
                                        new TokenItem(
                                                UUID.fromString(item.path("id").asText()),
                                                List.of("alpha")));
                            }
                            response = new TokenizeResponse(items, TOKENIZER);
                        } else if (path.equals("embed")) {
                            var items = new ArrayList<EmbedItem>();
                            for (var item : request.path("texts")) {
                                var vector = new ArrayList<>(Collections.nCopies(1024, 0.0));
                                vector.set(0, 1.0);
                                items.add(
                                        new EmbedItem(
                                                UUID.fromString(item.path("id").asText()), vector));
                            }
                            response = new EmbedResponse(items, 1024, MODEL);
                        } else if (path.equals("rerank")) {
                            onRerank.run();
                            response =
                                    new RerankResponse(
                                            List.of(
                                                    new RerankItem(
                                                            foreignId
                                                                    ? UUID.randomUUID()
                                                                    : UUID.fromString(
                                                                            request.path(
                                                                                            "candidates")
                                                                                    .get(0)
                                                                                    .path("chunkId")
                                                                                    .asText()),
                                                            0.7)),
                                            RERANK);
                        } else {
                            status = 404;
                            response = Map.of();
                        }
                        byte[] bytes = JSON.writeValueAsBytes(response);
                        if (!readiness && retryHeader != null)
                            exchange.getResponseHeaders().set("Retry-After", retryHeader);
                        exchange.getResponseHeaders().set("Content-Type", "application/json");
                        exchange.sendResponseHeaders(status == 0 ? 200 : status, bytes.length);
                        try (var output = exchange.getResponseBody()) {
                            output.write(bytes);
                        }
                    });
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
