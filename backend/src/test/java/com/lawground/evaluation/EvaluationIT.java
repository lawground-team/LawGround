package com.lawground.evaluation;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lawground.auth.MemberResolver;
import com.lawground.global.config.JsonData;
import com.lawground.global.error.ApiException;
import com.lawground.integration.ai.config.AiProperties;
import com.lawground.integration.ai.config.VerificationProperties;
import com.lawground.integration.ai.support.VerifierTestServer;
import com.lawground.job.config.LiveQueueConfiguration;
import com.lawground.job.service.JobStore;
import com.lawground.job.service.LiveJobWorker;
import com.lawground.job.service.LiveRunPlan;
import com.lawground.law.dto.LawImport;
import com.lawground.law.search.LiveLawIndexService;
import com.lawground.law.service.LawImportService;
import com.lawground.trace.service.VerificationEvidence;
import java.time.Clock;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.amqp.rabbit.core.*;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;

/** Synthetic labels/inference, real database/queue. Scores certify plumbing, not legal quality. */
@SpringBootTest(
        properties = {
            "lawground.analysis.live.enabled=true",
            "lawground.analysis.verification.enabled=true",
            "lawground.analysis.mock-enabled=false",
            "lawground.analysis.listener-enabled=false",
            "lawground.analysis.recovery-delay-ms=3600000",
            "lawground.evaluation.enabled=true"
        })
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class EvaluationIT {
    static final UUID MANAGER = UUID.randomUUID(),
            OTHER = UUID.randomUUID(),
            NORMAL = UUID.randomUUID(),
            LAW = UUID.randomUUID();
    static final VerifierTestServer HTTP = new VerifierTestServer();

    @Container @ServiceConnection
    static final PostgreSQLContainer<?> pg =
            new PostgreSQLContainer<>(
                    DockerImageName.parse("pgvector/pgvector:0.8.2-pg16")
                            .asCompatibleSubstituteFor("postgres"));

    @Container @ServiceConnection
    static final RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:4.2.9-management");

    @DynamicPropertySource
    static void settings(DynamicPropertyRegistry r) {
        r.add("lawground.evaluation.managers", () -> MANAGER + "," + OTHER);
        r.add("lawground.evaluation.build-revision", () -> "d".repeat(40));
        r.add("lawground.analysis.live.law-ids", LAW::toString);
        r.add("lawground.analysis.live.top-k", () -> 1);
        r.add("lawground.analysis.live.candidate-limit", () -> 2);
        r.add("lawground.ai.base-url", HTTP::url);
        r.add("lawground.ai.api-key", () -> VerifierTestServer.KEY);
        r.add("lawground.ai.embedding-model-revision", () -> VerifierTestServer.MODEL);
        r.add("lawground.ai.tokenizer-version", () -> VerifierTestServer.TOKENIZER);
        r.add("lawground.ai.reranker-model-revision", () -> VerifierTestServer.RERANK);
        r.add("lawground.analysis.verification.model-name", () -> VerifierTestServer.NAME);
        r.add("lawground.analysis.verification.model-revision", () -> VerifierTestServer.VERIFIER);
        r.add("lawground.analysis.verification.prompt-version", () -> VerifierTestServer.PROMPT);
    }

    @Autowired GoldenSetService golden;
    @Autowired EvaluationService evaluation;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired LawImportService imports;
    @Autowired LiveLawIndexService indexes;
    @Autowired LiveJobWorker worker;
    @Autowired MockMvc mvc;
    @Autowired RabbitTemplate rabbitTemplate;
    @Autowired RabbitListenerEndpointRegistry listeners;
    @Autowired ApplicationContext context;
    Set<UUID> articles;

    @BeforeEach
    void setup() {
        HTTP.reset();
        for (var id : List.of(MANAGER, OTHER, NORMAL))
            jdbc.update(
                    "INSERT INTO members(id,display_name,status) VALUES (?,'Synthetic evaluation actor','ACTIVE') ON CONFLICT(id) DO UPDATE SET status='ACTIVE'",
                    id);
        jdbc.update(
                "INSERT INTO laws(id,source_provider,source_law_id,title) VALUES (?,'LAW_GO_KR','SYNTHETIC-EVALUATION','SYNTHETIC EVALUATION') ON CONFLICT DO NOTHING",
                LAW);
        var source = new ArrayList<LawImport.Article>();
        for (int i = 1; i <= 2; i++) {
            String text = "alpha 합성 근거 " + i;
            source.add(
                    new LawImport.Article(
                            "a" + i,
                            "TEST " + i,
                            text,
                            List.of(
                                    new LawImport.Span(
                                            0, text.codePointCount(0, text.length()), text))));
        }
        var imported =
                imports.importVersion(
                        new LawImport(
                                "LAW_GO_KR",
                                "SYNTHETIC-EVALUATION",
                                "v1",
                                "SYNTHETIC EVALUATION",
                                LocalDate.of(2024, 1, 1),
                                null,
                                "VERIFIED",
                                "https://www.law.go.kr/TEST-ONLY",
                                Map.of("synthetic", true),
                                source));
        articles = Set.copyOf(imported.articleIds());
        for (var chunk : imported.chunkIds()) indexes.indexChunk(chunk);
        HTTP.reset();
        new RabbitAdmin(rabbitTemplate.getConnectionFactory())
                .purgeQueue(LiveQueueConfiguration.QUEUE, false);
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        assertThat(HTTP.violations).isEmpty();
    }

    @AfterAll
    static void stop() {
        HTTP.close();
    }

    <T> T actor(UUID id, Callable<T> body) throws Exception {
        var previous = SecurityContextHolder.getContext();
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(
                new UsernamePasswordAuthenticationToken(id.toString(), null, List.of()));
        SecurityContextHolder.setContext(context);
        try {
            return body.call();
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }

    ObjectNode input(String stem, String date) {
        var data =
                json.createObjectNode()
                        .put("subject", "BROKER_LAW")
                        .put("stem", stem)
                        .put("questionType", "SELECT_INCORRECT")
                        .put("providedAnswerNumber", 2)
                        .put("referenceDate", date);
        var choices = data.putArray("choices");
        for (int i = 1; i <= 5; i++) choices.addObject().put("number", i).put("text", "합성 선지 " + i);
        return data;
    }

    UUID question(String kind) throws Exception {
        var response =
                mvc.perform(
                                post("/api/v1/questions")
                                        .with(user(MANAGER.toString()))
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                json.writeValueAsBytes(
                                                        input(
                                                                kind + " " + UUID.randomUUID(),
                                                                "2025-10-25"))))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse();
        return UUID.fromString(json.readTree(response.getContentAsByteArray()).path("id").asText());
    }

    GoldenSetService.Dataset set(GoldenSetService.Split split) throws Exception {
        return actor(
                MANAGER,
                () ->
                        golden.create(
                                "SYNTHETIC " + UUID.randomUUID(),
                                "v1",
                                split,
                                GoldenSetService.Origin.SYNTHETIC));
    }

    GoldenSetService.Case add(
            UUID set, UUID q, String group, GoldenSetService.Verdict verdict, Set<UUID> expected)
            throws Exception {
        return actor(
                MANAGER,
                () ->
                        golden.addCase(
                                set,
                                q,
                                1,
                                group,
                                verdict,
                                expected,
                                "SECRET_LABEL_NOT_FOR_MODELS; synthetic only"));
    }

    GoldenSetService.Dataset frozenOne() throws Exception {
        var set = set(GoldenSetService.Split.DEV);
        add(
                set.id(),
                question("normal"),
                UUID.randomUUID().toString(),
                GoldenSetService.Verdict.SUPPORTED,
                articles);
        return actor(MANAGER, () -> golden.freeze(set.id()));
    }

    List<UUID> jobs(UUID run) {
        return jdbc.query(
                "SELECT j.id FROM processing_jobs j JOIN trace_runs r ON r.id=j.run_id WHERE r.evaluation_run_id=? ORDER BY j.id",
                (rs, n) -> rs.getObject(1, UUID.class),
                run);
    }

    void process(UUID run) {
        jobs(run).forEach(worker::process);
    }

    @Test
    void scoresRealQueueOutputsWithFailureAndZeroEvidenceHoldDenominators() throws Exception {
        var set = set(GoldenSetService.Split.DEV);
        add(
                set.id(),
                question("supported"),
                UUID.randomUUID().toString(),
                GoldenSetService.Verdict.SUPPORTED,
                articles);
        add(
                set.id(),
                question("hold"),
                UUID.randomUUID().toString(),
                GoldenSetService.Verdict.HOLD,
                Set.of());
        add(
                set.id(),
                question("technical"),
                UUID.randomUUID().toString(),
                GoldenSetService.Verdict.REJECTED,
                articles);
        actor(MANAGER, () -> golden.freeze(set.id()));
        HTTP.onVerify =
                () -> {
                    var stem = HTTP.verifyRequests.getLast().at("/questionSnapshot/stem").asText();
                    HTTP.verifyStatus = stem.startsWith("technical") ? 503 : 0;
                };
        HTTP.transform =
                n -> {
                    var stem = HTTP.verifyRequests.getLast().at("/questionSnapshot/stem").asText();
                    if (stem.startsWith("hold")) {
                        n.put("verdict", "HOLD");
                        n.putArray("evidences");
                    }
                    return n;
                };
        var initial = actor(MANAGER, () -> evaluation.start(set.id(), UUID.randomUUID()));
        assertThat(initial.pending()).isEqualTo(3);
        assertThat(initial.metrics()).isNull();
        listeners.start();
        EvaluationStore.Report report = initial;
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (report.pending() > 0 && System.nanoTime() < deadline) {
                Thread.sleep(20);
                report = actor(MANAGER, () -> evaluation.refresh(initial.id()));
            }
        } finally {
            listeners.stop();
        }
        assertThat(report.status()).isEqualTo("COMPLETED");
        assertThat(report.dataOrigin()).isEqualTo("SYNTHETIC");
        assertThat(report.executionMode()).isEqualTo("LIVE");
        assertThat(report.succeeded()).isEqualTo(2);
        assertThat(report.failed()).isEqualTo(1);
        assertThat(report.metrics().path("recallAt5").asDouble()).isEqualTo(0.25);
        assertThat(report.metrics().path("hitAt5").asDouble()).isEqualTo(0.5);
        assertThat(report.metrics().path("verificationAccuracy").asDouble())
                .isCloseTo(2.0 / 3.0, within(1e-12));
        assertThat(report.metrics().path("searchCases").asInt()).isEqualTo(2);
        assertThat(report.metrics().path("verdictCases").asInt()).isEqualTo(3);
        assertThat(report.metrics().path("technicalFailures").asInt()).isEqualTo(1);
        assertThat(report.versions().path("sourceCommit").asText()).isEqualTo("d".repeat(40));
        assertThat(report.versions().path("goldenSetHash").asText()).hasSize(64);
        var model = json.writeValueAsString(HTTP.verifyRequests);
        assertThat(model)
                .doesNotContain(
                        "expectedVerdict",
                        "expectedArticles",
                        "leakageGroup",
                        "sourceNote",
                        "SECRET_LABEL_NOT_FOR_MODELS");
        int count = HTTP.posts.size();
        var repeated = actor(MANAGER, () -> evaluation.refresh(initial.id()));
        assertThat(repeated.metrics()).isEqualTo(report.metrics());
        assertThat(HTTP.posts).hasSize(count);
        assertThat(repeated.items()).hasSize(3);
    }

    @Test
    void freezesReviewAndRejectsDirectLabelMutation() throws Exception {
        var set = set(GoldenSetService.Split.DEV);
        assertThatThrownBy(() -> actor(MANAGER, () -> golden.freeze(set.id())))
                .isInstanceOf(ApiException.class);
        var q = question("review");
        var c =
                add(
                        set.id(),
                        q,
                        UUID.randomUUID().toString(),
                        GoldenSetService.Verdict.SUPPORTED,
                        Set.of());
        assertThatThrownBy(() -> actor(MANAGER, () -> golden.freeze(set.id())))
                .isInstanceOf(ApiException.class);
        actor(
                MANAGER,
                () -> {
                    golden.removeCase(set.id(), c.id());
                    return null;
                });
        var fixed =
                add(
                        set.id(),
                        q,
                        UUID.randomUUID().toString(),
                        GoldenSetService.Verdict.SUPPORTED,
                        articles);
        actor(MANAGER, () -> golden.freeze(set.id()));
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE golden_cases SET expected_verdict='HOLD' WHERE id=?",
                                        fixed.id()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "DELETE FROM golden_case_evidences WHERE case_id=?",
                                        fixed.id()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE golden_sets SET status='DRAFT',frozen_at=NULL WHERE id=?",
                                        set.id()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(actor(MANAGER, () -> golden.freeze(set.id())).status()).isEqualTo("FROZEN");
    }

    @Test
    void rejectsDevFinalLeakageByGroupAndExactInput() throws Exception {
        String group = UUID.randomUUID().toString();
        var dev = set(GoldenSetService.Split.DEV);
        var fin = set(GoldenSetService.Split.FINAL);
        add(dev.id(), question("dev"), group, GoldenSetService.Verdict.SUPPORTED, articles);
        add(fin.id(), question("final"), group, GoldenSetService.Verdict.SUPPORTED, articles);
        actor(MANAGER, () -> golden.freeze(dev.id()));
        assertThatThrownBy(() -> actor(MANAGER, () -> golden.freeze(fin.id())))
                .isInstanceOf(ApiException.class);
        var sameQuestion = question("same");
        var d = set(GoldenSetService.Split.DEV);
        var f = set(GoldenSetService.Split.FINAL);
        add(
                d.id(),
                sameQuestion,
                UUID.randomUUID().toString(),
                GoldenSetService.Verdict.SUPPORTED,
                articles);
        add(
                f.id(),
                sameQuestion,
                UUID.randomUUID().toString(),
                GoldenSetService.Verdict.SUPPORTED,
                articles);
        actor(MANAGER, () -> golden.freeze(d.id()));
        assertThatThrownBy(() -> actor(MANAGER, () -> golden.freeze(f.id())))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void concurrentFreezeCannotLeakAcrossSplits() throws Exception {
        String group = UUID.randomUUID().toString();
        var dev = set(GoldenSetService.Split.DEV);
        var fin = set(GoldenSetService.Split.FINAL);
        add(
                dev.id(),
                question("dev-concurrent"),
                group,
                GoldenSetService.Verdict.SUPPORTED,
                articles);
        add(
                fin.id(),
                question("final-concurrent"),
                group,
                GoldenSetService.Verdict.SUPPORTED,
                articles);
        var pool = Executors.newFixedThreadPool(2);
        var gate = new CountDownLatch(1);
        try {
            var futures = new ArrayList<Future<Boolean>>();
            for (UUID id : List.of(dev.id(), fin.id()))
                futures.add(
                        pool.submit(
                                () -> {
                                    gate.await();
                                    try {
                                        actor(MANAGER, () -> golden.freeze(id));
                                        return true;
                                    } catch (ApiException expected) {
                                        return false;
                                    }
                                }));
            gate.countDown();
            int frozen = 0;
            for (var future : futures) if (future.get(15, TimeUnit.SECONDS)) frozen++;
            assertThat(frozen).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void managerAndDatasetOwnershipAreRequired() throws Exception {
        assertThatThrownBy(
                        () ->
                                actor(
                                        NORMAL,
                                        () ->
                                                golden.create(
                                                        "normal",
                                                        "v1",
                                                        GoldenSetService.Split.DEV,
                                                        GoldenSetService.Origin.SYNTHETIC)))
                .isInstanceOf(ApiException.class);
        var set = frozenOne();
        assertThatThrownBy(() -> actor(OTHER, () -> golden.get(set.id())))
                .isInstanceOf(ApiException.class);
        SecurityContextHolder.clearContext();
        assertThatThrownBy(() -> golden.get(set.id())).isInstanceOf(ApiException.class);
        jdbc.update("UPDATE members SET status='DEACTIVATED' WHERE id=?", MANAGER);
        assertThatThrownBy(() -> actor(MANAGER, () -> golden.get(set.id())))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void retriesStartAndRefreshWithoutDuplicatingRunsAndKeepsEvaluationPrivate() throws Exception {
        var set = frozenOne();
        UUID key = UUID.randomUUID();
        var run = actor(MANAGER, () -> evaluation.start(set.id(), key));
        assertThat(actor(MANAGER, () -> evaluation.start(set.id(), key)).id()).isEqualTo(run.id());
        assertThat(jobs(run.id())).hasSize(1);
        var other = frozenOne();
        assertThatThrownBy(() -> actor(MANAGER, () -> evaluation.start(other.id(), key)))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> actor(OTHER, () -> evaluation.get(run.id())))
                .isInstanceOf(ApiException.class);
        process(run.id());
        var report = actor(MANAGER, () -> evaluation.refresh(run.id()));
        assertThat(report.succeeded()).isEqualTo(1);
        UUID trace = report.items().getFirst().traceRunId();
        UUID job = jobs(run.id()).getFirst();
        for (String uri :
                List.of(
                        "/api/v1/trace-runs/" + trace,
                        "/api/v1/trace-runs/" + trace + "/result",
                        "/api/v1/jobs/" + job))
            mvc.perform(get(uri).with(user(MANAGER.toString()))).andExpect(status().isNotFound());
        UUID q =
                jdbc.queryForObject(
                        "SELECT question_id FROM trace_runs WHERE id=?", UUID.class, trace);
        mvc.perform(get("/api/v1/questions/" + q + "/trace-runs").with(user(MANAGER.toString())))
                .andExpect(jsonPath("$.items").isEmpty());
        var created =
                mvc.perform(
                                post("/api/v1/questions/" + q + "/trace-runs")
                                        .with(user(MANAGER.toString()))
                                        .with(csrf())
                                        .header("Idempotency-Key", trace)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"questionRevision\":1}"))
                        .andExpect(status().isAccepted())
                        .andReturn()
                        .getResponse();
        assertThat(json.readTree(created.getContentAsByteArray()).path("runId").asText())
                .isNotEqualTo(trace.toString());
        actor(MANAGER, () -> evaluation.refresh(run.id()));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM evaluation_results WHERE evaluation_run_id=?",
                                Integer.class,
                                run.id()))
                .isEqualTo(1);
    }

    @Test
    void usesFrozenProblemInsteadOfEditedQuestion() throws Exception {
        var set = set(GoldenSetService.Split.DEV);
        UUID q = question("original snapshot");
        var c =
                add(
                        set.id(),
                        q,
                        UUID.randomUUID().toString(),
                        GoldenSetService.Verdict.SUPPORTED,
                        articles);
        actor(MANAGER, () -> golden.freeze(set.id()));
        mvc.perform(
                        put("/api/v1/questions/" + q)
                                .with(user(MANAGER.toString()))
                                .with(csrf())
                                .header("If-Match", "\"1\"")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsBytes(
                                                input(
                                                        "EDITED SHOULD NOT REPLACE GOLDEN",
                                                        "2025-10-25"))))
                .andExpect(status().isOk());
        var run = actor(MANAGER, () -> evaluation.start(set.id(), UUID.randomUUID()));
        process(run.id());
        var result = actor(MANAGER, () -> evaluation.refresh(run.id()));
        assertThat(result.succeeded()).isEqualTo(1);
        var in = HTTP.verifyRequests.getFirst().path("questionSnapshot");
        assertThat(in.path("stem").asText()).startsWith("original snapshot");
        assertThat(in.path("revision").asInt()).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT input_hash FROM trace_runs WHERE evaluation_run_id=?",
                                String.class,
                                run.id()))
                .isEqualTo(c.inputHash());
    }

    @Test
    void deletedQuestionsFailWithoutAiAndRemainInDenominator() throws Exception {
        var set = set(GoldenSetService.Split.DEV);
        UUID q = question("deleted");
        add(
                set.id(),
                q,
                UUID.randomUUID().toString(),
                GoldenSetService.Verdict.SUPPORTED,
                articles);
        actor(MANAGER, () -> golden.freeze(set.id()));
        mvc.perform(
                        delete("/api/v1/questions/" + q)
                                .with(user(MANAGER.toString()))
                                .with(csrf())
                                .header("If-Match", "\"1\""))
                .andExpect(status().isNoContent());
        var run = actor(MANAGER, () -> evaluation.start(set.id(), UUID.randomUUID()));
        assertThat(jobs(run.id())).isEmpty();
        var result = actor(MANAGER, () -> evaluation.refresh(run.id()));
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.metrics().path("searchCases").asInt()).isEqualTo(1);
        assertThat(result.metrics().path("recallAt5").asDouble()).isZero();
        assertThat(result.metrics().path("technicalFailures").asInt()).isEqualTo(1);
        assertThat(HTTP.posts).isEmpty();
    }

    @Test
    void managerRevocationPreventsWorkerUseAndResultBindingsCannotBeForged() throws Exception {
        var set = frozenOne();
        var run = actor(MANAGER, () -> evaluation.start(set.id(), UUID.randomUUID()));
        jdbc.update("UPDATE members SET status='DEACTIVATED' WHERE id=?", MANAGER);
        process(run.id());
        assertThat(HTTP.posts).isEmpty();
        jdbc.update("UPDATE members SET status='ACTIVE' WHERE id=?", MANAGER);
        var report = actor(MANAGER, () -> evaluation.refresh(run.id()));
        assertThat(report.failed()).isEqualTo(1);
        assertThat(report.items().getFirst().errorCode()).isEqualTo("ACCESS_DENIED");
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE evaluation_results SET recall_at_5=1,hit_at_5=true WHERE evaluation_run_id=?",
                                        run.id()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        var second = actor(MANAGER, () -> evaluation.start(set.id(), UUID.randomUUID()));
        process(second.id());
        var secondReport = actor(MANAGER, () -> evaluation.refresh(second.id()));
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE evaluation_results SET trace_run_id=? WHERE evaluation_run_id=?",
                                        secondReport.items().getFirst().traceRunId(),
                                        run.id()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test
    void scoresRejectedAndKeepsConfigurationImmutable() throws Exception {
        var set = set(GoldenSetService.Split.DEV);
        add(
                set.id(),
                question("rejected"),
                UUID.randomUUID().toString(),
                GoldenSetService.Verdict.REJECTED,
                articles);
        actor(MANAGER, () -> golden.freeze(set.id()));
        HTTP.transform =
                n -> {
                    n.put("verdict", "REJECTED").put("reasonCode", "CONTRADICTS_SOURCE");
                    return n;
                };
        var run = actor(MANAGER, () -> evaluation.start(set.id(), UUID.randomUUID()));
        process(run.id());
        var report = actor(MANAGER, () -> evaluation.refresh(run.id()));
        assertThat(report.succeeded()).isEqualTo(1);
        assertThat(report.metrics().path("verificationAccuracy").asDouble()).isEqualTo(1);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE evaluation_runs SET versions='{}'::jsonb WHERE id=?",
                                        run.id()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test
    void missingHistoricalCorpusDoesNotBecomeSuccessfulHold() throws Exception {
        UUID q = question("past hold");
        mvc.perform(
                        put("/api/v1/questions/" + q)
                                .with(user(MANAGER.toString()))
                                .with(csrf())
                                .header("If-Match", "\"1\"")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsBytes(input("past hold", "2020-01-01"))))
                .andExpect(status().isOk());
        var set = set(GoldenSetService.Split.DEV);
        actor(
                MANAGER,
                () ->
                        golden.addCase(
                                set.id(),
                                q,
                                2,
                                UUID.randomUUID().toString(),
                                GoldenSetService.Verdict.HOLD,
                                Set.of(),
                                "synthetic historical case"));
        actor(MANAGER, () -> golden.freeze(set.id()));
        var run = actor(MANAGER, () -> evaluation.start(set.id(), UUID.randomUUID()));
        var report = actor(MANAGER, () -> evaluation.refresh(run.id()));
        assertThat(report.failed()).isEqualTo(1);
        assertThat(report.metrics().path("recallAt5").isNull()).isTrue();
        assertThat(report.metrics().path("searchCases").asInt()).isZero();
        assertThat(report.metrics().path("verdictCases").asInt()).isEqualTo(1);
        assertThat(report.metrics().path("verificationAccuracy").asDouble()).isZero();
        assertThat(HTTP.posts).isEmpty();
    }

    @Test
    void queuedQuestionDeletionCancelsEvaluationWithoutLosingTheCase() throws Exception {
        var set = frozenOne();
        var run = actor(MANAGER, () -> evaluation.start(set.id(), UUID.randomUUID()));
        UUID q = actor(MANAGER, () -> golden.cases(set.id())).getFirst().questionId();
        mvc.perform(
                        delete("/api/v1/questions/" + q)
                                .with(user(MANAGER.toString()))
                                .with(csrf())
                                .header("If-Match", "\"1\""))
                .andExpect(status().isNoContent());
        process(run.id());
        var report = actor(MANAGER, () -> evaluation.refresh(run.id()));
        assertThat(report.failed()).isEqualTo(1);
        assertThat(report.items().getFirst().errorCode()).isEqualTo("CANCELLED");
        assertThat(report.metrics().path("technicalFailures").asInt()).isEqualTo(1);
        assertThat(HTTP.posts).isEmpty();
    }

    @Test
    void expectedArticlesOutsideSelectedCorpusAreExplicitConfigurationFailures() throws Exception {
        String source = "other synthetic law";
        var imported =
                imports.importVersion(
                        new LawImport(
                                "LAW_GO_KR",
                                "SYNTHETIC-OTHER-" + UUID.randomUUID(),
                                "v1",
                                "SYNTHETIC OTHER",
                                LocalDate.of(2024, 1, 1),
                                null,
                                "VERIFIED",
                                "https://www.law.go.kr/TEST-ONLY",
                                Map.of("synthetic", true),
                                List.of(
                                        new LawImport.Article(
                                                "a1",
                                                "TEST",
                                                source,
                                                List.of(
                                                        new LawImport.Span(
                                                                0, source.length(), source))))));
        var set = set(GoldenSetService.Split.DEV);
        add(
                set.id(),
                question("outside corpus"),
                UUID.randomUUID().toString(),
                GoldenSetService.Verdict.SUPPORTED,
                Set.copyOf(imported.articleIds()));
        actor(MANAGER, () -> golden.freeze(set.id()));
        var run = actor(MANAGER, () -> evaluation.start(set.id(), UUID.randomUUID()));
        var report = actor(MANAGER, () -> evaluation.refresh(run.id()));
        assertThat(report.failed()).isEqualTo(1);
        assertThat(report.items().getFirst().errorCode()).isEqualTo("EVALUATION_CORPUS_MISMATCH");
        assertThat(report.metrics().path("searchCases").asInt()).isEqualTo(1);
        assertThat(HTTP.posts).isEmpty();
    }

    EvaluationProperties redeployedSettings() {
        return new EvaluationProperties(true, Set.of(MANAGER, OTHER), 20, "e".repeat(40));
    }

    EvaluationAccess redeployedAccess() {
        return new EvaluationAccess(redeployedSettings(), context.getBean(MemberResolver.class));
    }

    TransactionTemplate transaction() {
        return new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
    }

    @Test
    void differentCodeRevisionCannotClaimOrAdvanceAnEvaluationJob() throws Exception {
        var set = frozenOne();
        var run = actor(MANAGER, () -> evaluation.start(set.id(), UUID.randomUUID()));
        var differentWorker =
                new JobStore(
                        jdbc,
                        context.getBean(JsonData.class),
                        context.getBean(Clock.class),
                        context.getBean(VerificationEvidence.class),
                        context.getBean(LiveRunPlan.class),
                        redeployedAccess(),
                        context.getBean(com.lawground.trace.service.ChoiceAnalysisStore.class),
                        context.getBean(com.lawground.batch.BatchAccess.class));
        JobStore.Claim redeployedClaim =
                transaction()
                        .execute(
                                tx ->
                                        differentWorker.claim(
                                                jobs(run.id()).getFirst(), "redeployed", "LIVE"));
        assertThat(redeployedClaim).isNull();
        var report = actor(MANAGER, () -> evaluation.refresh(run.id()));
        assertThat(report.failed()).isEqualTo(1);
        assertThat(report.items().getFirst().errorCode()).isEqualTo("EVALUATION_VERSION_MISMATCH");
        assertThat(report.versions().path("sourceCommit").asText()).isEqualTo("d".repeat(40));

        var inFlight = actor(MANAGER, () -> evaluation.start(set.id(), UUID.randomUUID()));
        var claim =
                context.getBean(JobStore.class)
                        .claim(jobs(inFlight.id()).getFirst(), "original", "LIVE");
        assertThat(claim).isNotNull();
        JobStore.Start redeployedStage =
                transaction()
                        .execute(tx -> differentWorker.beginStage(claim, JobStore.Stage.SEARCH));
        assertThat(redeployedStage).isEqualTo(JobStore.Start.LOST);
        assertThat(actor(MANAGER, () -> evaluation.refresh(inFlight.id())).failed()).isEqualTo(1);
        assertThat(HTTP.posts).isEmpty();
    }

    @Test
    void differentCodeRevisionCanReadButCannotReconcileAnEvaluation() throws Exception {
        var set = frozenOne();
        var run = actor(MANAGER, () -> evaluation.start(set.id(), UUID.randomUUID()));
        process(run.id());
        var differentStore =
                new EvaluationStore(
                        jdbc,
                        context.getBean(JsonData.class),
                        redeployedAccess(),
                        redeployedSettings(),
                        golden,
                        context.getBean(AiProperties.class),
                        context.getBean(VerificationProperties.class),
                        context.getBean(Clock.class),
                        context);
        assertThat(actor(MANAGER, () -> differentStore.report(run.id())).pending()).isEqualTo(1);
        assertThatThrownBy(
                        () ->
                                actor(
                                        MANAGER,
                                        () ->
                                                transaction()
                                                        .execute(
                                                                tx ->
                                                                        differentStore.refresh(
                                                                                run.id()))))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("revision");
        assertThat(actor(MANAGER, () -> evaluation.get(run.id())).metrics()).isNull();
        assertThat(actor(MANAGER, () -> evaluation.refresh(run.id())).succeeded()).isEqualTo(1);
    }

    @Test
    void upgradesV006WithoutEditingChecksumsOrExistingMembers() {
        var ds =
                new org.springframework.jdbc.datasource.DriverManagerDataSource(
                        pg.getJdbcUrl()
                                + (pg.getJdbcUrl().contains("?") ? "&" : "?")
                                + "currentSchema=eval_upgrade",
                        pg.getUsername(),
                        pg.getPassword());
        var base =
                org.flywaydb.core.Flyway.configure().dataSource(ds).defaultSchema("eval_upgrade");
        base.target("6").load().migrate();
        var sql = new JdbcTemplate(ds);
        UUID existing = UUID.randomUUID();
        sql.update(
                "INSERT INTO members(id,display_name,status) VALUES (?,'upgrade preservation','ACTIVE')",
                existing);
        var before =
                sql.queryForList(
                        "SELECT version,checksum FROM flyway_schema_history WHERE type='SQL' ORDER BY installed_rank");
        org.flywaydb.core.Flyway.configure()
                .dataSource(ds)
                .defaultSchema("eval_upgrade")
                .load()
                .migrate();
        assertThat(
                        sql.queryForList(
                                "SELECT version,checksum FROM flyway_schema_history WHERE type='SQL' AND installed_rank<=6 ORDER BY installed_rank"))
                .isEqualTo(before);
        assertThat(
                        sql.queryForObject(
                                "SELECT count(*) FROM flyway_schema_history WHERE type='SQL'",
                                Integer.class))
                .isEqualTo(9);
        assertThat(
                        sql.queryForObject(
                                "SELECT display_name FROM members WHERE id=?",
                                String.class,
                                existing))
                .isEqualTo("upgrade preservation");
        assertThat(sql.queryForObject("SELECT count(*) FROM golden_sets", Integer.class)).isZero();
    }
}
