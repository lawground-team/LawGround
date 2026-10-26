package com.lawground.workflow;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lawground.integration.ai.support.VerifierTestServer;
import com.lawground.job.config.LiveQueueConfiguration;
import com.lawground.job.service.*;
import com.lawground.law.dto.LawImport;
import com.lawground.law.search.*;
import com.lawground.law.service.LawImportService;
import com.lawground.trace.service.LiveVerifierService;
import com.rabbitmq.client.Channel;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
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

/** Real infrastructure, future verifier protocol with synthetic responses, no actual LLM. */
@SpringBootTest(
        properties = {
            "lawground.analysis.live.enabled=true",
            "lawground.analysis.verification.enabled=true",
            "lawground.analysis.mock-enabled=false",
            "lawground.analysis.listener-enabled=false",
            "lawground.analysis.recovery-delay-ms=3600000"
        })
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class VerificationWorkerIT {
    static final UUID LAW = UUID.randomUUID();
    static final VerifierTestServer HTTP = new VerifierTestServer();
    static final String SOURCE = "alpha 앞😀근거 문장\n뒤";

    @Container @ServiceConnection
    static final PostgreSQLContainer<?> pg =
            new PostgreSQLContainer<>(
                    DockerImageName.parse("pgvector/pgvector:0.8.2-pg16")
                            .asCompatibleSubstituteFor("postgres"));

    @Container @ServiceConnection
    static final RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:4.2.9-management");

    @DynamicPropertySource
    static void settings(DynamicPropertyRegistry r) {
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

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired LawImportService imports;
    @Autowired LiveLawIndexService indexer;
    @Autowired JobStore jobs;
    @Autowired LiveJobWorker worker;
    @Autowired LiveRunPlan plans;
    @Autowired LiveRetrievalService retrieval;
    @Autowired LiveVerifierService verifier;
    @Autowired RabbitTemplate template;
    @Autowired RabbitListenerEndpointRegistry listeners;
    UUID owner, other, chunk, version;

    @BeforeEach
    void setup() {
        HTTP.reset();
        owner = UUID.randomUUID();
        other = UUID.randomUUID();
        for (var id : List.of(owner, other))
            jdbc.update(
                    "INSERT INTO members(id,display_name,status) VALUES (?,'Synthetic verifier member','ACTIVE')",
                    id);
        jdbc.update(
                "INSERT INTO laws(id,source_provider,source_law_id,title) VALUES (?,'LAW_GO_KR','SYNTHETIC-VERIFY','SYNTHETIC') ON CONFLICT DO NOTHING",
                LAW);
        var imported =
                imports.importVersion(
                        new LawImport(
                                "LAW_GO_KR",
                                "SYNTHETIC-VERIFY",
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
                                                SOURCE,
                                                List.of(
                                                        new LawImport.Span(
                                                                0,
                                                                SOURCE.codePointCount(
                                                                        0, SOURCE.length()),
                                                                SOURCE))))));
        version = imported.versionId();
        chunk = imported.chunkIds().getFirst();
        indexer.indexChunk(chunk);
        HTTP.reset();
        new RabbitAdmin(template.getConnectionFactory())
                .purgeQueue(LiveQueueConfiguration.QUEUE, false);
    }

    @AfterEach
    void check() {
        assertThat(HTTP.violations).isEmpty();
    }

    @AfterAll
    static void close() {
        HTTP.close();
    }

    ObjectNode input(String stem) {
        var data =
                json.createObjectNode()
                        .put("subject", "BROKER_LAW")
                        .put("stem", stem)
                        .put("questionType", "SELECT_INCORRECT")
                        .put("providedAnswerNumber", 2)
                        .put("referenceDate", "2025-10-25");
        var c = data.putArray("choices");
        for (int i = 1; i <= 5; i++) c.addObject().put("number", i).put("text", "합성 선지 " + i);
        return data;
    }

    void capture(String contract, String schema, JsonNode body) throws Exception {
        var folder = java.nio.file.Path.of("build", "contract-responses");
        java.nio.file.Files.createDirectories(folder);
        json.writeValue(
                folder.resolve(UUID.randomUUID() + ".json").toFile(),
                Map.of("contract", contract, "schema", schema, "body", body));
    }

    JsonNode request(MockHttpServletRequestBuilder builder, int expected) throws Exception {
        var response =
                mvc.perform(builder.with(user(owner.toString())).with(csrf()))
                        .andExpect(status().is(expected))
                        .andReturn()
                        .getResponse();
        if (response.getContentAsByteArray().length == 0) return null;
        var body = json.readTree(response.getContentAsByteArray());
        String uri =
                builder.buildRequest(new org.springframework.mock.web.MockServletContext())
                        .getRequestURI();
        String schema =
                expected >= 400
                        ? "Error"
                        : uri.endsWith("/result")
                                ? "Result"
                                : uri.endsWith("/cancel")
                                        ? "CancelAccepted"
                                        : uri.contains("/jobs/")
                                                ? "Job"
                                                : uri.endsWith("/trace-runs")
                                                        ? "TraceAccepted"
                                                        : uri.contains("/trace-runs/")
                                                                ? "TraceRun"
                                                                : "Question";
        capture("external", schema, body);
        return body;
    }

    UUID question() throws Exception {
        return UUID.fromString(
                request(
                                post("/api/v1/questions")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                json.writeValueAsBytes(
                                                        input(
                                                                "원래 합성 문제 옳지 않은 것은? "
                                                                        + UUID.randomUUID()))),
                                201)
                        .path("id")
                        .asText());
    }

    JsonNode trace(UUID q) throws Exception {
        return request(
                post("/api/v1/questions/" + q + "/trace-runs")
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"questionRevision\":1}"),
                202);
    }

    UUID job(JsonNode t) {
        return UUID.fromString(t.path("jobId").asText());
    }

    UUID run(JsonNode t) {
        return UUID.fromString(t.path("runId").asText());
    }

    String jobStatus(JsonNode t) {
        return jdbc.queryForObject(
                "SELECT status FROM processing_jobs WHERE id=?", String.class, job(t));
    }

    JsonNode checkpoint(JsonNode t) throws Exception {
        return json.readTree(
                jdbc.queryForObject(
                        "SELECT checkpoint::text FROM trace_runs WHERE id=?",
                        String.class,
                        run(t)));
    }

    int evidenceCount(JsonNode t) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM evidence_results WHERE run_id=?", Integer.class, run(t));
    }

    void failed(JsonNode t, String reason) {
        assertThat(jobStatus(t)).isEqualTo("FAILED");
        assertThat(
                        jdbc.queryForMap(
                                "SELECT verdict,reason_code FROM trace_runs WHERE id=?", run(t)))
                .containsEntry("verdict", null)
                .containsEntry("reason_code", reason);
        assertThat(evidenceCount(t)).isZero();
    }

    void expire(JsonNode t) {
        jdbc.update(
                "UPDATE processing_jobs SET lease_until=now()-interval '1 second' WHERE id=?",
                job(t));
    }

    void transformVerdict(String verdict) {
        HTTP.transform =
                n -> {
                    n.put("verdict", verdict);
                    n.put(
                            "reasonCode",
                            verdict.equals("HOLD")
                                    ? "INSUFFICIENT_EVIDENCE"
                                    : verdict.equals("REJECTED")
                                            ? "CONTRADICTS_SOURCE"
                                            : "SUPPORTED_BY_SOURCE");
                    if (verdict.equals("HOLD")) n.putArray("evidences");
                    return n;
                };
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUPPORTED", "HOLD", "REJECTED"})
    void realQueueCompletesCoreVerdictsAndReturnsAuthoritativeEvidence(String verdict)
            throws Exception {
        transformVerdict(verdict);
        var trace = trace(question());
        HTTP.onVerify =
                () ->
                        assertThat(
                                        jdbc.queryForObject(
                                                "SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND state='idle in transaction'",
                                                Integer.class))
                                .isZero();
        listeners.start();
        try {
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (!"SUCCEEDED".equals(jobStatus(trace)) && System.nanoTime() < end)
                Thread.sleep(20);
        } finally {
            listeners.stop();
        }
        assertThat(jobStatus(trace)).isEqualTo("SUCCEEDED");
        var result = request(get("/api/v1/trace-runs/" + run(trace) + "/result"), 200);
        assertThat(result.path("verdict").asText()).isEqualTo(verdict);
        assertThat(result.path("executionMode").asText()).isEqualTo("LIVE");
        assertThat(result.at("/versions/verifierModelRevision").asText())
                .isEqualTo(VerifierTestServer.VERIFIER);
        assertThat(result.at("/versions/promptVersion").asText())
                .isEqualTo(VerifierTestServer.PROMPT);
        assertThat(result.path("versions").size()).isEqualTo(6);
        assertThat(result.path("choiceAnalyses").isNull()).isTrue();
        assertThat(evidenceCount(trace)).isEqualTo(verdict.equals("HOLD") ? 0 : 1);
        if (!verdict.equals("HOLD")) {
            assertThat(result.at("/evidences/0/quote").asText()).isEqualTo(SOURCE);
            assertThat(result.at("/evidences/0/lawVersionId").asText())
                    .isEqualTo(version.toString());
            assertThat(result.at("/evidences/0/end").asInt())
                    .isEqualTo(SOURCE.codePointCount(0, SOURCE.length()));
        }
        assertThat(checkpoint(trace).path("VERIFY").asBoolean()).isTrue();
        int calls = HTTP.posts.size();
        worker.process(job(trace));
        assertThat(HTTP.posts).hasSize(calls);
        var in = HTTP.verifyRequests.getFirst();
        capture("internal", "VerifyInput", in);
        capture("internal", "VerifyOutput", checkpoint(trace).at("/verification/result"));
        mvc.perform(get("/api/v1/app/config"))
                .andExpect(jsonPath("$.analysisAvailable").value(false));
        mvc.perform(
                        get("/api/v1/trace-runs/" + run(trace) + "/result")
                                .with(user(other.toString())))
                .andExpect(status().isNotFound());
    }

    @Test
    void verifyRetryReusesSearchAndHonorsRetryAfter() throws Exception {
        var trace = trace(question());
        HTTP.verifyStatus = 429;
        HTTP.retryAfter = "30";
        worker.process(job(trace));
        assertThat(jobStatus(trace)).isEqualTo("RETRY_WAIT");
        assertThat(checkpoint(trace).path("SEARCH").asBoolean()).isTrue();
        assertThat(checkpoint(trace).path("VERIFY").asBoolean()).isFalse();
        assertThat(HTTP.posts).containsExactly("tokenize", "embed", "rerank", "verify");
        assertThat(jobs.claim(job(trace), "early", "LIVE")).isNull();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT extract(epoch from next_attempt_at-updated_at) FROM processing_jobs WHERE id=?",
                                Double.class,
                                job(trace)))
                .isBetween(29.0, 31.0);
        jdbc.update(
                "UPDATE processing_jobs SET next_attempt_at=now()-interval '1 second' WHERE id=?",
                job(trace));
        HTTP.verifyStatus = 0;
        HTTP.retryAfter = null;
        worker.process(job(trace));
        assertThat(jobStatus(trace)).isEqualTo("SUCCEEDED");
        assertThat(HTTP.posts).containsExactly("tokenize", "embed", "rerank", "verify", "verify");
    }

    record Saved(
            JsonNode trace,
            JobStore.Claim claim,
            LiveRunPlan.VerifiedConfig config,
            LiveRetrievalService.Result retrieval) {}

    Saved savedVerification() throws Exception {
        var trace = trace(question());
        var claim = jobs.claim(job(trace), "crashed-worker", "LIVE");
        var config = plans.readVerified(jobs.liveWork(claim).config());
        jobs.beginStage(claim, JobStore.Stage.PREPARE);
        jobs.finishStage(claim, JobStore.Stage.PREPARE);
        jobs.beginStage(claim, JobStore.Stage.SEARCH);
        var found = retrieval.search(plans.query(config.retrieval(), claim.snapshot()));
        jobs.recordLiveRetrieval(claim, found);
        jobs.beginStage(claim, JobStore.Stage.VERIFY);
        var response = verifier.verify(claim.snapshot(), config, found);
        assertThat(jobs.recordVerification(claim, config, found, response)).isTrue();
        return new Saved(trace, claim, config, found);
    }

    @Test
    void verifiedCheckpointResumesAndRejectsStaleCommit() throws Exception {
        var saved = savedVerification();
        int calls = HTTP.posts.size();
        expire(saved.trace());
        worker.process(job(saved.trace()));
        assertThat(jobStatus(saved.trace())).isEqualTo("SUCCEEDED");
        assertThat(HTTP.posts).hasSize(calls);
        assertThat(jobs.completeVerified(saved.claim(), saved.config(), saved.retrieval()))
                .isFalse();
        assertThat(evidenceCount(saved.trace())).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"cancel", "delete"})
    void cancelledVerifiedCheckpointCannotFinish(String action) throws Exception {
        var saved = savedVerification();
        if (action.equals("cancel"))
            request(post("/api/v1/trace-runs/" + run(saved.trace()) + "/cancel"), 202);
        else
            request(
                    delete("/api/v1/questions/" + saved.claim().questionId())
                            .header("If-Match", "\"1\""),
                    204);
        assertThat(jobs.completeVerified(saved.claim(), saved.config(), saved.retrieval()))
                .isFalse();
        worker.process(job(saved.trace()));
        assertThat(jobStatus(saved.trace())).isEqualTo("CANCELLED");
        assertThat(evidenceCount(saved.trace())).isZero();
    }

    @Test
    void responseIsBoundToMetadataSentBeforeHttp() throws Exception {
        var trace = trace(question());
        HTTP.onVerify =
                () -> jdbc.update("UPDATE laws SET title='CHANGED DURING HTTP' WHERE id=?", LAW);
        try {
            worker.process(job(trace));
            failed(trace, "CORPUS_CHANGED");
            assertThat(checkpoint(trace).path("VERIFY").asBoolean()).isFalse();
        } finally {
            jdbc.update("UPDATE laws SET title='SYNTHETIC' WHERE id=?", LAW);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"hash", "quote"})
    void corruptVerifiedCheckpointCannotBeCompleted(String part) throws Exception {
        var saved = savedVerification();
        int calls = HTTP.posts.size();
        if (part.equals("hash"))
            jdbc.update(
                    "UPDATE trace_runs SET checkpoint=jsonb_set(checkpoint,'{verification,requestHash}',to_jsonb(?::text)) WHERE id=?",
                    "0".repeat(64),
                    run(saved.trace()));
        else
            jdbc.update(
                    "UPDATE trace_runs SET checkpoint=jsonb_set(checkpoint,'{verification,result,evidences,0,quote}',to_jsonb('변조'::text)) WHERE id=?",
                    run(saved.trace()));
        expire(saved.trace());
        worker.process(job(saved.trace()));
        failed(saved.trace(), part.equals("hash") ? "CHECKPOINT_INVALID" : "AI_CONTRACT_INVALID");
        assertThat(HTTP.posts).hasSize(calls);
    }

    @ParameterizedTest
    @ValueSource(strings = {"cancel", "delete", "revoke", "lease"})
    void lateVerificationCannotPersistAfterStateChanges(String action) throws Exception {
        UUID q = question();
        var trace = trace(q);
        HTTP.onVerify =
                () -> {
                    try {
                        switch (action) {
                            case "cancel" ->
                                    request(
                                            post("/api/v1/trace-runs/" + run(trace) + "/cancel"),
                                            202);
                            case "delete" ->
                                    request(
                                            delete("/api/v1/questions/" + q)
                                                    .header("If-Match", "\"1\""),
                                            204);
                            case "revoke" ->
                                    jdbc.update(
                                            "UPDATE members SET status='DEACTIVATED' WHERE id=?",
                                            owner);
                            case "lease" -> expire(trace);
                        }
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                };
        worker.process(job(trace));
        assertThat(evidenceCount(trace)).isZero();
        assertThat(checkpoint(trace).path("VERIFY").asBoolean()).isFalse();
        assertThat(jobStatus(trace))
                .isEqualTo(
                        action.equals("revoke")
                                ? "FAILED"
                                : action.equals("lease") ? "RUNNING" : "CANCELLED");
        if (action.equals("lease")) {
            HTTP.onVerify = () -> {};
            worker.process(job(trace));
            assertThat(jobStatus(trace)).isEqualTo("SUCCEEDED");
            assertThat(evidenceCount(trace)).isEqualTo(1);
        }
    }

    @Test
    void inputRevisionAndOldRetrievalPipelineCannotBeSilentlyUpgraded() throws Exception {
        UUID q = question();
        var trace = trace(q);
        request(
                put("/api/v1/questions/" + q)
                        .header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(input("수정된 새 문제"))),
                200);
        worker.process(job(trace));
        assertThat(HTTP.verifyRequests.getFirst().at("/questionSnapshot/stem").asText())
                .startsWith("원래 합성 문제");
        assertThat(HTTP.verifyRequests.getFirst().at("/questionSnapshot/revision").asInt())
                .isEqualTo(1);
        var old = trace(question());
        jdbc.update(
                "UPDATE trace_runs SET pipeline_version=?,config_snapshot=config_snapshot->'retrieval' WHERE id=?",
                LiveRunPlan.PIPELINE,
                run(old));
        int verifies = HTTP.verifyRequests.size();
        worker.process(job(old));
        failed(old, "VERIFIER_NOT_CONFIGURED");
        assertThat(HTTP.verifyRequests).hasSize(verifies);
    }

    @Test
    void pinMismatchAndCorpusMutationFailBeforeFinalPersistence() throws Exception {
        var pin = trace(question());
        jdbc.update(
                "UPDATE trace_runs SET config_snapshot=jsonb_set(config_snapshot,'{verification,promptVersion}',to_jsonb('other-prompt'::text)) WHERE id=?",
                run(pin));
        worker.process(job(pin));
        failed(pin, "PROMPT_VERSION_MISMATCH");
        assertThat(HTTP.posts).isEmpty();
        var changed = trace(question());
        HTTP.onVerify =
                () ->
                        jdbc.update(
                                "UPDATE law_versions SET applicability_status='REVIEW_REQUIRED' WHERE id=?",
                                version);
        try {
            worker.process(job(changed));
            failed(changed, "CORPUS_CHANGED");
            assertThat(checkpoint(changed).path("VERIFY").asBoolean()).isFalse();
        } finally {
            jdbc.update(
                    "UPDATE law_versions SET applicability_status='VERIFIED' WHERE id=?", version);
        }
    }

    @Test
    void nonReadyVerifierAndUnsupportedExtensionNeverProduceLegalResults() throws Exception {
        var missing = trace(question());
        HTTP.readiness = "missing";
        worker.process(job(missing));
        failed(missing, "MODEL_NOT_READY");
        assertThat(HTTP.verifyRequests).isEmpty();
        HTTP.readiness = "ready";
        HTTP.transform =
                n -> {
                    n.putArray("choiceAnalyses");
                    return n;
                };
        var extra = trace(question());
        worker.process(job(extra));
        failed(extra, "VERIFICATION_EXTENSION_UNSUPPORTED");
    }

    @Test
    void databaseFailureRollsBackAllEvidenceAndLeavesDeliveryUnacked() throws Exception {
        HTTP.transform =
                n -> {
                    var array = n.putArray("evidences");
                    array.addObject()
                            .put("evidenceKey", "e1")
                            .put("chunkId", chunk.toString())
                            .put("quote", "alpha")
                            .put("start", 0)
                            .put("end", 5);
                    array.addObject()
                            .put("evidenceKey", "e2")
                            .put("chunkId", chunk.toString())
                            .put("quote", "앞😀")
                            .put("start", 6)
                            .put("end", 8);
                    return n;
                };
        var saved = savedVerification();
        int calls = HTTP.posts.size();
        expire(saved.trace());
        jdbc.execute(
                "CREATE FUNCTION synthetic_evidence_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.ordinal=1 THEN RAISE EXCEPTION 'synthetic rollback' USING ERRCODE='23514'; END IF; RETURN NEW; END; $$");
        jdbc.execute(
                "CREATE TRIGGER synthetic_evidence_failure BEFORE INSERT ON evidence_results FOR EACH ROW EXECUTE FUNCTION synthetic_evidence_failure()");
        var channel = mock(Channel.class);
        var props = new MessageProperties();
        props.setDeliveryTag(7);
        try {
            var message =
                    new Message(
                            json.writeValueAsBytes(
                                    new JobPublisher.Envelope(
                                            1, job(saved.trace()), UUID.randomUUID())),
                            props);
            assertThatThrownBy(() -> worker.receive(message, channel))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
            verify(channel, never()).basicAck(anyLong(), anyBoolean());
            assertThat(evidenceCount(saved.trace())).isZero();
            assertThat(jobStatus(saved.trace())).isEqualTo("RUNNING");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT verdict FROM trace_runs WHERE id=?",
                                    String.class,
                                    run(saved.trace())))
                    .isNull();
        } finally {
            jdbc.execute("DROP TRIGGER synthetic_evidence_failure ON evidence_results");
            jdbc.execute("DROP FUNCTION synthetic_evidence_failure()");
        }
        expire(saved.trace());
        worker.process(job(saved.trace()));
        assertThat(jobStatus(saved.trace())).isEqualTo("SUCCEEDED");
        assertThat(evidenceCount(saved.trace())).isEqualTo(2);
        assertThat(HTTP.posts).hasSize(calls);
    }
}
