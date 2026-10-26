package com.lawground.batch;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lawground.auth.MemberResolver;
import com.lawground.global.config.JsonData;
import com.lawground.global.error.ApiException;
import com.lawground.integration.ai.support.VerifierTestServer;
import com.lawground.job.service.*;
import com.lawground.law.dto.LawImport;
import com.lawground.law.search.LiveLawIndexService;
import com.lawground.law.service.LawImportService;
import com.lawground.question.dto.CreateQuestionRequest;
import com.lawground.question.service.QuestionService;
import com.lawground.trace.service.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
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

@SpringBootTest(
        properties = {
            "lawground.batch.enabled=true",
            "lawground.analysis.live.enabled=true",
            "lawground.analysis.verification.enabled=true",
            "lawground.analysis.mock-enabled=false",
            "lawground.analysis.listener-enabled=false",
            "lawground.analysis.recovery-delay-ms=3600000"
        })
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Testcontainers
class ExamBatchIT {
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
    static void properties(DynamicPropertyRegistry r) {
        r.add("lawground.batch.managers", () -> MANAGER + "," + OTHER);
        r.add("lawground.batch.build-revision", () -> "d".repeat(40));
        r.add("lawground.analysis.live.law-ids", LAW::toString);
        r.add("lawground.ai.base-url", HTTP::url);
        r.add("lawground.ai.api-key", () -> VerifierTestServer.KEY);
        r.add("lawground.ai.embedding-model-revision", () -> VerifierTestServer.MODEL);
        r.add("lawground.ai.tokenizer-version", () -> VerifierTestServer.TOKENIZER);
        r.add("lawground.ai.reranker-model-revision", () -> VerifierTestServer.RERANK);
        r.add("lawground.analysis.verification.model-name", () -> VerifierTestServer.NAME);
        r.add("lawground.analysis.verification.model-revision", () -> VerifierTestServer.VERIFIER);
        r.add("lawground.analysis.verification.prompt-version", () -> VerifierTestServer.PROMPT);
    }

    @Autowired ExamBatchService batches;
    @Autowired JdbcTemplate jdbc;
    @Autowired QuestionService questions;
    @Autowired ObjectMapper json;
    @Autowired LawImportService imports;
    @Autowired LiveLawIndexService indexes;
    @Autowired LiveJobWorker worker;
    @Autowired JobStore jobs;
    @Autowired MockMvc mvc;
    @Autowired ApplicationContext context;

    @BeforeEach
    void setup() {
        HTTP.reset();
        for (var id : List.of(MANAGER, OTHER, NORMAL))
            jdbc.update(
                    "INSERT INTO members(id,display_name,status) VALUES (?,'Synthetic batch actor','ACTIVE') ON CONFLICT(id) DO UPDATE SET status='ACTIVE'",
                    id);
        jdbc.update(
                "INSERT INTO laws(id,source_provider,source_law_id,title) VALUES (?,'LAW_GO_KR','SYNTHETIC-BATCH','Synthetic batch law') ON CONFLICT DO NOTHING",
                LAW);
        String text = "alpha 합성 배치 검수 원문";
        var law =
                imports.importVersion(
                        new LawImport(
                                "LAW_GO_KR",
                                "SYNTHETIC-BATCH",
                                "v1",
                                "Synthetic batch law",
                                LocalDate.of(2024, 1, 1),
                                null,
                                "VERIFIED",
                                "https://www.law.go.kr/TEST-ONLY",
                                Map.of("synthetic", true),
                                List.of(
                                        new LawImport.Article(
                                                "a1",
                                                "TEST",
                                                text,
                                                List.of(
                                                        new LawImport.Span(
                                                                0,
                                                                text.codePointCount(
                                                                        0, text.length()),
                                                                text))))));
        law.chunkIds().forEach(indexes::indexChunk);
        HTTP.reset();
    }

    @AfterEach
    void check() {
        SecurityContextHolder.clearContext();
        assertThat(HTTP.violations).isEmpty();
    }

    @AfterAll
    static void close() {
        HTTP.close();
    }

    <T> T actor(UUID id, Callable<T> call) throws Exception {
        var previous = SecurityContextHolder.getContext();
        var security = SecurityContextHolder.createEmptyContext();
        security.setAuthentication(
                new UsernamePasswordAuthenticationToken(id.toString(), null, List.of()));
        SecurityContextHolder.setContext(security);
        try {
            return call.call();
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }

    UUID question(boolean approved) throws Exception {
        var id =
                actor(
                        OTHER,
                        () ->
                                questions
                                        .create(
                                                new CreateQuestionRequest(
                                                        CreateQuestionRequest.Subject.BROKER_LAW,
                                                        "합성 기출 " + UUID.randomUUID(),
                                                        CreateQuestionRequest.QuestionType
                                                                .SELECT_INCORRECT,
                                                        java.util.stream.IntStream.rangeClosed(1, 5)
                                                                .mapToObj(
                                                                        i ->
                                                                                new CreateQuestionRequest
                                                                                        .Choice(
                                                                                        i,
                                                                                        "alpha 합성 선지 "
                                                                                                + i))
                                                                .toList(),
                                                        2,
                                                        LocalDate.of(2025, 10, 25)))
                                        .id());
        if (approved)
            jdbc.update(
                    "UPDATE questions SET visibility='PUBLIC',source_kind='EXAM_IMPORT',answer_source='OFFICIAL_VERIFIED',source_key=?,source_url='https://example.test/synthetic' WHERE id=?",
                    id.toString(),
                    id);
        return id;
    }

    ExamBatchStore.Report start(UUID... q) throws Exception {
        return actor(MANAGER, () -> batches.start(UUID.randomUUID(), "SYNTHETIC-v1", List.of(q)));
    }

    List<UUID> jobIds(UUID id) {
        return jdbc.query(
                "SELECT j.id FROM processing_jobs j JOIN trace_runs r ON r.id=j.run_id WHERE r.batch_run_id=? ORDER BY j.id",
                (rs, n) -> rs.getObject(1, UUID.class),
                id);
    }

    ExamBatchStore.Report refresh(UUID id) throws Exception {
        return actor(MANAGER, () -> batches.refresh(id));
    }

    @Test
    void permissionsDefaultsAndBoundedAdmission() throws Exception {
        assertThat(new BatchProperties(false, null, null, null).enabled()).isFalse();
        assertThat(new BatchProperties(false, null, null, null).maxItems()).isEqualTo(20);
        assertThatThrownBy(() -> new BatchProperties(true, Set.of(), 20, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BatchProperties(true, Set.of(MANAGER), 101, null))
                .isInstanceOf(IllegalArgumentException.class);
        var q = question(true);
        assertThatThrownBy(
                        () ->
                                actor(
                                        NORMAL,
                                        () -> batches.start(UUID.randomUUID(), "v1", List.of(q))))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("권한");
        assertThatThrownBy(() -> start(q, q)).isInstanceOf(ApiException.class);
        assertThatThrownBy(
                        () ->
                                actor(
                                        MANAGER,
                                        () -> batches.start(UUID.randomUUID(), "v1", List.of())))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> start(question(false))).isInstanceOf(ApiException.class);
    }

    @Test
    void concurrentIdenticalAdmissionsShareOneDurableJob() throws Exception {
        UUID q = question(true), key = UUID.randomUUID();
        var pool = Executors.newFixedThreadPool(2);
        try {
            var barrier = new CyclicBarrier(2);
            Callable<ExamBatchStore.Report> call =
                    () -> {
                        barrier.await(10, TimeUnit.SECONDS);
                        return actor(MANAGER, () -> batches.start(key, "SYNTHETIC-v1", List.of(q)));
                    };
            var first = pool.submit(call);
            var second = pool.submit(call);
            var a = first.get(20, TimeUnit.SECONDS);
            var b = second.get(20, TimeUnit.SECONDS);
            assertThat(a.id()).isEqualTo(b.id());
            assertThat(jobIds(a.id())).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void missingDateCorpusIsRecordedWithoutCreatingAnInvalidJob() throws Exception {
        var q = question(true);
        jdbc.update("UPDATE questions SET reference_date='2020-01-01' WHERE id=?", q);
        var run = start(q);
        assertThat(jobIds(run.id())).isEmpty();
        var done = refresh(run.id());
        assertThat(done.status()).isEqualTo("COMPLETED");
        assertThat(done.aggregate().path("failedQuestions").asInt()).isEqualTo(1);
        assertThat(done.aggregate().path("unprocessedChoices").asInt()).isEqualTo(5);
        assertThat(done.items().getFirst().traceRunId()).isNull();
    }

    @Test
    void frozenIdempotentBatchAndResumeDoNotDuplicateJobs() throws Exception {
        var q = question(true);
        var key = UUID.randomUUID();
        var first = actor(MANAGER, () -> batches.start(key, "SYNTHETIC-v1", List.of(q)));
        var again = actor(MANAGER, () -> batches.start(key, "SYNTHETIC-v1", List.of(q)));
        assertThat(again.id()).isEqualTo(first.id());
        assertThat(jobIds(first.id())).hasSize(1);
        assertThatThrownBy(() -> actor(MANAGER, () -> batches.start(key, "changed", List.of(q))))
                .isInstanceOf(ApiException.class);
        assertThat(first.configuration().path("items").get(0).path("admission").path("corpus"))
                .isNotNull();
        assertThat(first.configuration().path("sourceCommit").asText()).isEqualTo("d".repeat(40));
        assertThat(refresh(first.id()).aggregate().path("pendingQuestions").asInt()).isEqualTo(1);
        worker.process(jobIds(first.id()).getFirst());
        var finalReport = refresh(first.id());
        assertThat(finalReport.status()).isEqualTo("COMPLETED");
        assertThat(finalReport.aggregate().path("succeededQuestions").asInt()).isEqualTo(1);
        int calls = HTTP.verifyRequests.size();
        worker.process(jobIds(first.id()).getFirst());
        assertThat(refresh(first.id())).isEqualTo(finalReport);
        assertThat(HTTP.verifyRequests).hasSize(calls);
        assertThat(finalReport.aggregate().path("unclassifiedChoices").asInt()).isEqualTo(5);
        assertThat(finalReport.aggregate().path("missingChoiceAnalyses").asInt()).isEqualTo(5);
        assertThat(finalReport.aggregate().path("articles").get(0).path("questionCount").asInt())
                .isEqualTo(1);
    }

    @Test
    void failedAndPendingRemainInDenominatorAndHoldIsNotFailure() throws Exception {
        var run = start(question(true), question(true), question(true));
        var ids = jobIds(run.id());
        worker.process(ids.get(0));
        HTTP.verifyStatus = 503;
        worker.process(ids.get(1));
        HTTP.verifyStatus = 0;
        var partial = refresh(run.id());
        assertThat(partial.aggregate().path("totalQuestions").asInt()).isEqualTo(3);
        assertThat(partial.aggregate().path("failedQuestions").asInt()).isEqualTo(1);
        assertThat(partial.aggregate().path("pendingQuestions").asInt()).isEqualTo(1);
        assertThat(partial.aggregate().path("unprocessedChoices").asInt()).isEqualTo(10);
        HTTP.transform =
                n -> {
                    n.put("verdict", "HOLD");
                    n.putArray("evidences");
                    return n;
                };
        worker.process(ids.get(2));
        var done = refresh(run.id());
        assertThat(done.status()).isEqualTo("COMPLETED");
        assertThat(done.aggregate().path("verdictCounts").path("HOLD").asInt()).isEqualTo(1);
        assertThat(
                        done.aggregate().path("unclassifiedChoices").asInt()
                                + done.aggregate().path("unprocessedChoices").asInt())
                .isEqualTo(15);
        assertThat(done.aggregate().path("typeCounts")).isEmpty();
        assertThat(done.aggregate().path("countsAreUserErrorRates").asBoolean()).isFalse();
    }

    @Test
    void fiveProvidedChoicesAreCountedButNeverInventedAsTags() throws Exception {
        HTTP.transform =
                n -> {
                    var a = n.putArray("choiceAnalyses");
                    for (int i = 1; i <= 5; i++) {
                        var c =
                                a.addObject()
                                        .put("choiceNumber", i)
                                        .put("relation", i == 2 ? "CONTRADICTS" : "SUPPORTS")
                                        .put("explanation", "합성 설명");
                        c.putNull("correctedText");
                        c.putArray("tags");
                        c.putArray("evidenceKeys").add("e1");
                    }
                    return n;
                };
        var run = start(question(true));
        worker.process(jobIds(run.id()).getFirst());
        var a = refresh(run.id()).aggregate();
        assertThat(a.path("providedChoiceAnalyses").asInt()).isEqualTo(5);
        assertThat(a.path("missingChoiceAnalyses").asInt()).isZero();
        assertThat(a.path("classifiedChoices").asInt()).isZero();
        assertThat(a.path("unclassifiedChoices").asInt()).isEqualTo(5);
    }

    @Test
    void revokedChangedAndInactiveSourcesCannotBeClaimed() throws Exception {
        for (String change :
                List.of("visibility='PRIVATE'", "revision=revision+1", "deleted_at=now()")) {
            var q = question(true);
            var run = start(q);
            jdbc.update("UPDATE questions SET " + change + " WHERE id=?", q);
            worker.process(jobIds(run.id()).getFirst());
            assertThat(refresh(run.id()).aggregate().path("failedQuestions").asInt()).isEqualTo(1);
        }
        var run = start(question(true));
        jdbc.update("UPDATE members SET status='DEACTIVATED' WHERE id=?", MANAGER);
        worker.process(jobIds(run.id()).getFirst());
        jdbc.update("UPDATE members SET status='ACTIVE' WHERE id=?", MANAGER);
        assertThat(refresh(run.id()).aggregate().path("failedQuestions").asInt()).isEqualTo(1);
    }

    @Test
    void revokeDuringVerifyPreventsEvidenceAndChoicesPersistence() throws Exception {
        var q = question(true);
        var run = start(q);
        HTTP.onVerify =
                () -> jdbc.update("UPDATE questions SET visibility='PRIVATE' WHERE id=?", q);
        worker.process(jobIds(run.id()).getFirst());
        var done = refresh(run.id());
        assertThat(done.aggregate().path("failedQuestions").asInt()).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM evidence_results WHERE run_id=?",
                                Integer.class,
                                run.items().getFirst().traceRunId()))
                .isZero();
    }

    @Test
    void batchScopeIsHiddenFromUserApisAndOtherManagers() throws Exception {
        var q = question(true);
        var run = start(q);
        var trace = run.items().getFirst().traceRunId();
        var job = jobIds(run.id()).getFirst();
        for (var path :
                List.of(
                        "/trace-runs/" + trace,
                        "/trace-runs/" + trace + "/job",
                        "/trace-runs/" + trace + "/result",
                        "/trace-runs/" + trace + "/recommendations",
                        "/jobs/" + job))
            mvc.perform(get("/api/v1" + path).with(user(MANAGER.toString())))
                    .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/questions/" + q + "/trace-runs").with(user(MANAGER.toString())))
                .andExpect(jsonPath("$.items").isEmpty());
        assertThatThrownBy(() -> actor(OTHER, () -> batches.get(run.id())))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("찾을");
        mvc.perform(
                        post("/api/v1/trace-runs/" + trace + "/cancel")
                                .with(user(MANAGER.toString()))
                                .with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    void frozenAuditAndCrossBatchBindingAreEnforcedInDatabase() throws Exception {
        var q = question(true);
        var run = start(q);
        var other = start(question(true));
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE batch_runs SET config_snapshot='{}'::jsonb WHERE id=?",
                                        run.id()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE batch_items SET trace_run_id=? WHERE batch_run_id=?",
                                        other.items().getFirst().traceRunId(),
                                        run.id()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        worker.process(jobIds(run.id()).getFirst());
        refresh(run.id());
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE batch_items SET status='PENDING',verdict=NULL WHERE batch_run_id=?",
                                        run.id()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void anotherBuildCannotClaimFrozenBatch() throws Exception {
        var run = start(question(true));
        var different =
                new BatchAccess(
                        new BatchProperties(true, Set.of(MANAGER), 20, "e".repeat(40)),
                        context.getBean(MemberResolver.class));
        var store =
                new JobStore(
                        jdbc,
                        context.getBean(JsonData.class),
                        context.getBean(Clock.class),
                        context.getBean(VerificationEvidence.class),
                        context.getBean(LiveRunPlan.class),
                        context.getBean(com.lawground.evaluation.EvaluationAccess.class),
                        context.getBean(ChoiceAnalysisStore.class),
                        different);
        var tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        JobStore.Claim claim =
                tx.execute(
                        t -> store.claim(jobIds(run.id()).getFirst(), "different-build", "LIVE"));
        assertThat(claim).isNull();
        assertThat(refresh(run.id()).items().getFirst().errorCode())
                .isEqualTo("BATCH_VERSION_MISMATCH");
    }

    @Test
    void v008UpgradePreservesChecksumsAndExistingMember() {
        var ds =
                new org.springframework.jdbc.datasource.DriverManagerDataSource(
                        pg.getJdbcUrl()
                                + (pg.getJdbcUrl().contains("?") ? "&" : "?")
                                + "currentSchema=batch_upgrade",
                        pg.getUsername(),
                        pg.getPassword());
        var base =
                org.flywaydb.core.Flyway.configure().dataSource(ds).defaultSchema("batch_upgrade");
        base.target("8").load().migrate();
        var sql = new JdbcTemplate(ds);
        var before =
                sql.queryForList(
                        "SELECT version,checksum FROM flyway_schema_history WHERE type='SQL' ORDER BY installed_rank");
        UUID member = UUID.randomUUID();
        sql.update(
                "INSERT INTO members(id,display_name,status) VALUES (?,'preserved','ACTIVE')",
                member);
        base.target("latest").load().migrate();
        assertThat(
                        sql.queryForList(
                                "SELECT version,checksum FROM flyway_schema_history WHERE type='SQL' AND installed_rank<=8 ORDER BY installed_rank"))
                .isEqualTo(before);
        assertThat(
                        sql.queryForObject(
                                "SELECT display_name FROM members WHERE id=?",
                                String.class,
                                member))
                .isEqualTo("preserved");
        assertThat(
                        sql.queryForObject(
                                "SELECT count(*) FROM flyway_schema_history WHERE type='SQL'",
                                Integer.class))
                .isEqualTo(9);
    }
}
