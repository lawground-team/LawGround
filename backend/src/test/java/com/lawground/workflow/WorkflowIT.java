package com.lawground.workflow;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lawground.job.config.MockQueueConfiguration;
import com.lawground.job.service.*;
import com.lawground.law.dto.LawImport;
import com.lawground.law.search.*;
import com.lawground.law.service.LawImportService;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(
        properties = {
            "lawground.analysis.mock-enabled=true",
            "lawground.analysis.listener-enabled=false",
            "lawground.analysis.recovery-delay-ms=3600000"
        })
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class WorkflowIT {
    @Container @ServiceConnection
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(
                    DockerImageName.parse("pgvector/pgvector:0.8.2-pg16")
                            .asCompatibleSubstituteFor("postgres"));

    @Container @ServiceConnection
    static final RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:4.2.9-management");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired JobStore jobs;
    @Autowired JobPublisher publisher;
    @Autowired MockJobWorker worker;
    @Autowired LawImportService imports;
    @Autowired FixtureIndexWriter indexes;
    @Autowired HybridSearchService search;
    @Autowired RabbitTemplate template;
    @Autowired RabbitListenerEndpointRegistry listeners;
    @Autowired com.lawground.trace.service.EvidenceValidator evidence;
    UUID owner, other;

    @BeforeEach
    void setup() {
        owner = UUID.randomUUID();
        other = UUID.randomUUID();
        for (UUID id : List.of(owner, other))
            jdbc.update(
                    "INSERT INTO members(id,display_name,status) VALUES (?,'Synthetic workflow member','ACTIVE')",
                    id);
        new RabbitAdmin(template.getConnectionFactory())
                .purgeQueue(MockQueueConfiguration.QUEUE, false);
    }

    ObjectNode input(String stem) {
        var data =
                mapper.createObjectNode()
                        .put("subject", "BROKER_LAW")
                        .put("stem", stem)
                        .put("questionType", "SELECT_CORRECT")
                        .put("providedAnswerNumber", 2)
                        .put("referenceDate", "2025-10-25");
        var choices = data.putArray("choices");
        for (int n = 1; n <= 5; n++) choices.addObject().put("number", n).put("text", "합성 선지 " + n);
        return data;
    }

    JsonNode perform(MockHttpServletRequestBuilder request, int status) throws Exception {
        var response =
                mvc.perform(request.with(user(owner.toString())).with(csrf()))
                        .andExpect(status().is(status))
                        .andReturn()
                        .getResponse();
        var result =
                response.getContentAsByteArray().length == 0
                        ? null
                        : mapper.readTree(response.getContentAsByteArray());
        if (result != null) {
            String uri =
                    request.buildRequest(new org.springframework.mock.web.MockServletContext())
                            .getRequestURI();
            String schema =
                    status >= 400
                            ? "Error"
                            : uri.endsWith("/result")
                                    ? "Result"
                                    : uri.endsWith("/cancel")
                                            ? "CancelAccepted"
                                            : uri.contains("/jobs/")
                                                    ? "Job"
                                                    : uri.contains("/trace-runs/")
                                                            ? "TraceRun"
                                                            : uri.endsWith("/trace-runs")
                                                                    ? (status == 202
                                                                            ? "TraceAccepted"
                                                                            : "TraceRunList")
                                                                    : uri.equals(
                                                                                            "/api/v1/questions")
                                                                                    && status == 200
                                                                            ? "QuestionList"
                                                                            : "Question";
            var folder = java.nio.file.Path.of("build", "contract-responses");
            java.nio.file.Files.createDirectories(folder);
            mapper.writeValue(
                    folder.resolve(UUID.randomUUID() + ".json").toFile(),
                    Map.of("schema", schema, "body", result));
        }
        return result;
    }

    UUID question(String stem) throws Exception {
        return UUID.fromString(
                perform(
                                post("/api/v1/questions")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(mapper.writeValueAsBytes(input(stem))),
                                201)
                        .path("id")
                        .asText());
    }

    JsonNode trace(UUID q, UUID key) throws Exception {
        return perform(
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

    void waitPublished(UUID id) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!"QUEUED"
                        .equals(
                                jdbc.queryForObject(
                                        "SELECT status FROM processing_jobs WHERE id=?",
                                        String.class,
                                        id))
                && System.nanoTime() < end) Thread.sleep(10);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM processing_jobs WHERE id=?", String.class, id))
                .isEqualTo("QUEUED");
    }

    @Test
    void crudMaintainsFiveChoicesRevisionOwnerAndSoftDelete() throws Exception {
        UUID q = question("CRUD original");
        var changed = input("CRUD revised");
        ((ObjectNode) changed.withArray("choices").get(0)).put("text", "변경 선지😀");
        perform(
                put("/api/v1/questions/" + q)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(changed)),
                428);
        mvc.perform(
                        put("/api/v1/questions/" + q)
                                .with(user(other.toString()))
                                .with(csrf())
                                .header("If-Match", "\"1\"")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(mapper.writeValueAsBytes(changed)))
                .andExpect(status().isNotFound());
        var response =
                perform(
                        put("/api/v1/questions/" + q)
                                .header("If-Match", "\"1\"")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(mapper.writeValueAsBytes(changed)),
                        200);
        assertThat(response.path("revision").asLong()).isEqualTo(2);
        assertThat(response.path("choices").get(0).path("text").asText()).isEqualTo("변경 선지😀");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM question_choices WHERE question_id=?",
                                Integer.class,
                                q))
                .isEqualTo(5);
        perform(delete("/api/v1/questions/" + q).header("If-Match", "\"1\""), 409);
        perform(delete("/api/v1/questions/" + q).header("If-Match", "\"2\""), 204);
        perform(get("/api/v1/questions/" + q), 404);
        perform(delete("/api/v1/questions/" + q).header("If-Match", "\"3\""), 404);
        question("CRUD revised");
    }

    @Test
    void listIsStableSignedAndBoundToScopeAndPrincipal() throws Exception {
        UUID first = question("가😀".repeat(80)), second = question("Second library question");
        var page = perform(get("/api/v1/questions").param("limit", "1"), 200);
        assertThat(page.path("items")).hasSize(1);
        assertThat(page.path("items").get(0).path("id").asText()).isEqualTo(second.toString());
        assertThat(page.path("items").get(0).path("latestOwnRun").isNull()).isTrue();
        String cursor = page.path("nextCursor").asText();
        var next =
                perform(get("/api/v1/questions").param("limit", "1").param("cursor", cursor), 200);
        String preview = next.path("items").get(0).path("stemPreview").asText();
        assertThat(preview.codePointCount(0, preview.length())).isEqualTo(120);
        assertThat(next.path("items").get(0).path("id").asText()).isEqualTo(first.toString());
        perform(get("/api/v1/questions").param("cursor", cursor + "x"), 400);
        perform(get("/api/v1/questions").param("scope", "public").param("cursor", cursor), 400);
        mvc.perform(get("/api/v1/questions").with(user(other.toString())).param("cursor", cursor))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/questions")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/questions").param("scope", "public"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
        perform(get("/api/v1/questions").param("limit", "51"), 400);
    }

    @Test
    void concurrentEditsAllowExactlyOneExpectedRevision() throws Exception {
        UUID q = question("Concurrent edit");
        byte[] body = mapper.writeValueAsBytes(input("Concurrent changed"));
        var gate = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> call =
                    () -> {
                        gate.await();
                        return mvc.perform(
                                        put("/api/v1/questions/" + q)
                                                .with(user(owner.toString()))
                                                .with(csrf())
                                                .header("If-Match", "\"1\"")
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(body))
                                .andReturn()
                                .getResponse()
                                .getStatus();
                    };
            var a = pool.submit(call);
            var b = pool.submit(call);
            gate.countDown();
            assertThat(List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentIdempotencyProducesOneRunAndSnapshotSurvivesEdit() throws Exception {
        UUID q = question("Idempotent original"), key = UUID.randomUUID();
        var gate = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        JsonNode accepted;
        try {
            Callable<JsonNode> call =
                    () -> {
                        gate.await();
                        return trace(q, key);
                    };
            var a = pool.submit(call);
            var b = pool.submit(call);
            gate.countDown();
            accepted = a.get(15, TimeUnit.SECONDS);
            assertThat(b.get(15, TimeUnit.SECONDS)).isEqualTo(accepted);
        } finally {
            pool.shutdownNow();
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM trace_runs WHERE question_id=?",
                                Integer.class,
                                q))
                .isEqualTo(1);
        perform(
                put("/api/v1/questions/" + q)
                        .header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(input("Idempotent revised"))),
                200);
        assertThat(trace(q, key)).isEqualTo(accepted);
        UUID otherQ = question("Different idempotent payload");
        perform(
                post("/api/v1/questions/" + otherQ + "/trace-runs")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"questionRevision\":1}"),
                409);
        worker.process(job(accepted));
        var result = perform(get("/api/v1/trace-runs/" + run(accepted) + "/result"), 200);
        assertThat(result.path("inputSnapshot").path("stem").asText())
                .isEqualTo("Idempotent original");
        assertThat(result.path("executionMode").asText()).isEqualTo("MOCK");
        assertThat(result.path("verdict").asText()).isEqualTo("HOLD");
        assertThat(result.path("evidences")).isEmpty();
        assertThat(result.path("choiceAnalyses").isNull()).isTrue();
        var library = perform(get("/api/v1/questions"), 200);
        assertThat(library.path("items").toString()).contains(run(accepted).toString());
    }

    @Test
    void realRabbitMessageIsPersistentAndListenerCommitsBeforeAck() throws Exception {
        var accepted = trace(question("Rabbit listener flow"), UUID.randomUUID());
        var message = template.receive(MockQueueConfiguration.QUEUE, 5000);
        assertThat(message).isNotNull();
        var payload = mapper.readTree(message.getBody());
        assertThat(payload.size()).isEqualTo(3);
        assertThat(payload.path("jobId").asText()).isEqualTo(job(accepted).toString());
        assertThat(message.getMessageProperties().getReceivedDeliveryMode())
                .isEqualTo(MessageDeliveryMode.PERSISTENT);
        // Re-publish the same job twice; an actual manual-ACK listener must persist only one
        // completion.
        publisher.publish(job(accepted));
        publisher.publish(job(accepted));
        listeners.start();
        try {
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < end
                    && !"SUCCEEDED"
                            .equals(
                                    jdbc.queryForObject(
                                            "SELECT status FROM processing_jobs WHERE id=?",
                                            String.class,
                                            job(accepted)))) Thread.sleep(25);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT status FROM processing_jobs WHERE id=?",
                                    String.class,
                                    job(accepted)))
                    .isEqualTo("SUCCEEDED");
            assertThat(
                            perform(get("/api/v1/trace-runs/" + run(accepted) + "/result"), 200)
                                    .path("reasonCode")
                                    .asText())
                    .isEqualTo("MOCK_ONLY");
        } finally {
            listeners.stop();
        }
    }

    @Test
    void lostPublishAndAckRedeliveryRecoverWithoutRegression() throws Exception {
        var accepted = trace(question("Recovery flow"), UUID.randomUUID());
        UUID id = job(accepted);
        waitPublished(id);
        new RabbitAdmin(template.getConnectionFactory())
                .purgeQueue(MockQueueConfiguration.QUEUE, false);
        jdbc.update(
                "UPDATE processing_jobs SET status='PENDING',last_published_at=NULL,updated_at=now()-interval '10 seconds' WHERE id=?",
                id);
        assertThat(jobs.recoverable()).contains(id);
        publisher.recover();
        assertThat(template.receive(MockQueueConfiguration.QUEUE, 5000)).isNotNull();
        var claim = jobs.claim(id, "worker-a");
        assertThat(claim).isNotNull();
        jobs.published(
                id,
                jdbc.queryForObject(
                        "SELECT publish_attempts FROM processing_jobs WHERE id=?",
                        Integer.class,
                        id),
                true);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM processing_jobs WHERE id=?", String.class, id))
                .isEqualTo("RUNNING");
        assertThat(jobs.completeMock(claim)).isTrue();
        assertThat(jobs.completeMock(claim)).isFalse();
        worker.process(id);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT delivery_count FROM processing_jobs WHERE id=?",
                                Integer.class,
                                id))
                .isEqualTo(1);
    }

    @Test
    void expiredLeaseFencesOldWorkerAndCancelOrDeleteBlocksLateSave() throws Exception {
        var accepted = trace(question("Lease fencing"), UUID.randomUUID());
        UUID id = job(accepted);
        var old = jobs.claim(id, "old-worker");
        assertThat(jobs.claim(id, "duplicate-worker")).isNull();
        jdbc.update(
                "UPDATE processing_jobs SET lease_until=now()-interval '1 second' WHERE id=?", id);
        var current = jobs.claim(id, "new-worker");
        assertThat(current.fencingToken()).isGreaterThan(old.fencingToken());
        assertThat(jobs.heartbeat(old)).isFalse();
        assertThat(jobs.completeMock(old)).isFalse();
        assertThat(jobs.completeMock(current)).isTrue();
        var cancel = trace(question("Cancel late save"), UUID.randomUUID());
        var cancelledClaim = jobs.claim(job(cancel), "cancelled-worker");
        perform(
                post("/api/v1/trace-runs/" + run(cancel) + "/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"),
                400);
        perform(post("/api/v1/trace-runs/" + run(cancel) + "/cancel"), 202);
        perform(post("/api/v1/trace-runs/" + run(cancel) + "/cancel"), 200);
        assertThat(jobs.completeMock(cancelledClaim)).isFalse();
        perform(get("/api/v1/trace-runs/" + run(cancel) + "/result"), 409);
        UUID deletedQ = question("Delete late save");
        var deleted = trace(deletedQ, UUID.randomUUID());
        var deletedClaim = jobs.claim(job(deleted), "deleted-worker");
        perform(delete("/api/v1/questions/" + deletedQ).header("If-Match", "\"1\""), 204);
        assertThat(jobs.completeMock(deletedClaim)).isFalse();
        perform(get("/api/v1/jobs/" + job(deleted)), 404);
    }

    @Test
    void retriesArePerStageAndTechnicalFailureIsNeverHold() throws Exception {
        var accepted = trace(question("Retry budget"), UUID.randomUUID());
        UUID id = job(accepted);
        for (int attempt = 1; attempt <= 3; attempt++) {
            var claim = jobs.claim(id, "retry-worker");
            assertThat(claim).isNotNull();
            assertThat(jobs.beginStage(claim, JobStore.Stage.VERIFY))
                    .isEqualTo(JobStore.Start.STARTED);
            assertThat(jobs.retry(claim, "MOCK_TRANSIENT", null)).isTrue();
            if (attempt < 3) {
                assertThat(jobs.claim(id, "too-early")).isNull();
                jdbc.update(
                        "UPDATE processing_jobs SET next_attempt_at=now()-interval '1 second' WHERE id=?",
                        id);
            }
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM processing_jobs WHERE id=?", String.class, id))
                .isEqualTo("FAILED");
        var run = perform(get("/api/v1/trace-runs/" + run(accepted)), 200);
        assertThat(run.path("verdict").isNull()).isTrue();
        assertThat(run.path("status").asText()).isEqualTo("FAILED");
        var deadline = trace(question("Deadline budget"), UUID.randomUUID());
        jdbc.update(
                "UPDATE processing_jobs SET deadline_at=now()-interval '1 second' WHERE id=?",
                job(deadline));
        assertThat(jobs.claim(job(deadline), "late")).isNull();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT last_error_code FROM processing_jobs WHERE id=?",
                                String.class,
                                job(deadline)))
                .isEqualTo("DEADLINE_EXCEEDED");
    }

    @Test
    void inaccessibleRunsNeverExposeStateAndInvalidAdmissionLeavesNoJob() throws Exception {
        UUID q = question("Access test");
        perform(
                post("/api/v1/questions/" + q + "/trace-runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"questionRevision\":1}"),
                400);
        perform(
                post("/api/v1/questions/" + q + "/trace-runs")
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"questionRevision\":2}"),
                409);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM trace_runs WHERE question_id=?",
                                Integer.class,
                                q))
                .isZero();
        var accepted = trace(q, UUID.randomUUID());
        var jobState = perform(get("/api/v1/jobs/" + job(accepted)), 200);
        assertThat(jobState.path("stageAttempt").asInt()).isZero();
        assertThat(perform(get("/api/v1/questions/" + q + "/trace-runs"), 200).path("items"))
                .hasSize(1);
        for (String path :
                List.of(
                        "/api/v1/jobs/" + job(accepted),
                        "/api/v1/trace-runs/" + run(accepted),
                        "/api/v1/trace-runs/" + run(accepted) + "/result"))
            mvc.perform(get(path).with(user(other.toString()))).andExpect(status().isNotFound());
        mvc.perform(
                        post("/api/v1/questions/" + q + "/trace-runs")
                                .with(user(owner.toString()))
                                .header("Idempotency-Key", UUID.randomUUID())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"questionRevision\":1}"))
                .andExpect(status().isForbidden());
        perform(get("/api/v1/trace-runs/" + run(accepted) + "/result"), 409);
    }

    LawImport law(
            String sourceId,
            String version,
            LocalDate from,
            LocalDate to,
            String status,
            List<String> texts) {
        var articles = new ArrayList<LawImport.Article>();
        for (int i = 0; i < texts.size(); i++) {
            String text = texts.get(i);
            articles.add(
                    new LawImport.Article(
                            "제" + (i + 1) + "조",
                            "합성 제" + (i + 1) + "조",
                            text,
                            List.of(
                                    new LawImport.Span(
                                            0, text.codePointCount(0, text.length()), text))));
        }
        return new LawImport(
                "FIXTURE",
                sourceId,
                version,
                "합성 법령",
                from,
                to,
                status,
                "https://example.invalid/synthetic",
                Map.of("synthetic", true),
                articles);
    }

    @Test
    void lawImportIsAtomicImmutableAndSerializesConcurrentVersions() throws Exception {
        String source = "fixture-" + UUID.randomUUID();
        var command =
                law(
                        source,
                        "v1",
                        LocalDate.of(2024, 1, 1),
                        LocalDate.of(2025, 1, 1),
                        "VERIFIED",
                        List.of("가😀조건\n합성 원문"));
        var first = imports.importVersion(command);
        assertThat(imports.importVersion(command).reused()).isTrue();
        assertThat(imports.importVersion(command).chunkIds()).isEqualTo(first.chunkIds());
        assertThatThrownBy(
                        () ->
                                imports.importVersion(
                                        law(
                                                source,
                                                "v1",
                                                command.effectiveFrom(),
                                                command.effectiveTo(),
                                                "VERIFIED",
                                                List.of("다른 원문"))))
                .isInstanceOf(LawImportService.SourceConflict.class);
        assertThatThrownBy(
                        () ->
                                imports.importVersion(
                                        law(
                                                source,
                                                "overlap",
                                                LocalDate.of(2024, 6, 1),
                                                null,
                                                "VERIFIED",
                                                List.of("겹치는 버전"))))
                .isInstanceOf(LawImportService.SourceConflict.class);
        var second =
                imports.importVersion(
                        law(
                                source,
                                "v2",
                                LocalDate.of(2025, 1, 1),
                                null,
                                "VERIFIED",
                                List.of("개정 원문")));
        assertThat(second.versionId()).isNotEqualTo(first.versionId());
        mvc.perform(get("/api/v1/law-articles/" + first.articleIds().get(0)))
                .andExpect(status().isNotFound());
        var bad =
                new LawImport(
                        "FIXTURE",
                        "invalid-" + UUID.randomUUID(),
                        "v1",
                        "합성",
                        LocalDate.of(2024, 1, 1),
                        null,
                        "VERIFIED",
                        "https://example.invalid",
                        Map.of(),
                        List.of(
                                new LawImport.Article(
                                        "a",
                                        "a",
                                        "가😀나",
                                        List.of(new LawImport.Span(0, 2, "가😀")))));
        assertThatThrownBy(() -> imports.importVersion(bad))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM laws WHERE source_law_id=?",
                                Integer.class,
                                bad.sourceLawId()))
                .isZero();
        var gate = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        var concurrent =
                law(
                        "concurrent-" + UUID.randomUUID(),
                        "v1",
                        LocalDate.of(2024, 1, 1),
                        null,
                        "VERIFIED",
                        List.of("동시 수집"));
        try {
            Callable<LawImportService.Imported> call =
                    () -> {
                        gate.await();
                        return imports.importVersion(concurrent);
                    };
            var a = pool.submit(call);
            var b = pool.submit(call);
            gate.countDown();
            assertThat(a.get(15, TimeUnit.SECONDS).versionId())
                    .isEqualTo(b.get(15, TimeUnit.SECONDS).versionId());
        } finally {
            pool.shutdownNow();
        }
    }

    float[] vector(float a, float b) {
        var result = new float[1024];
        result[0] = a;
        result[1] = b;
        return result;
    }

    @Test
    void realVectorFtsAndRrfRespectDateVersionsHashesAndReadiness() {
        String source = "search-" + UUID.randomUUID();
        var old =
                imports.importVersion(
                        law(
                                source,
                                "old",
                                LocalDate.of(2024, 1, 1),
                                LocalDate.of(2025, 1, 1),
                                "VERIFIED",
                                List.of("과거 원문")));
        var current =
                imports.importVersion(
                        law(
                                source,
                                "current",
                                LocalDate.of(2025, 1, 1),
                                null,
                                "VERIFIED",
                                List.of("현재 A", "현재 B", "현재 C")));
        UUID lawId =
                jdbc.queryForObject(
                        "SELECT law_id FROM law_versions WHERE id=?",
                        UUID.class,
                        current.versionId());
        String model = "fixture-vector-v1", tokenizer = "fixture-tokenizer-v1";
        indexes.write(old.chunkIds().get(0), model, tokenizer, vector(1, 0), List.of("중개", "보수"));
        indexes.write(
                current.chunkIds().get(0),
                model,
                tokenizer,
                vector(1, 0),
                List.of("중개", "멀리", "떨어진", "보수"));
        indexes.write(
                current.chunkIds().get(1), model, tokenizer, vector(0, 1), List.of("중개", "보수"));
        indexes.write(
                current.chunkIds().get(2), model, tokenizer, vector(-1, 0), List.of("다른", "용어"));
        var query =
                new HybridSearchService.Query(
                        Set.of(lawId),
                        LocalDate.of(2025, 1, 1),
                        model,
                        tokenizer,
                        LawImportService.CHUNKER,
                        vector(1, 0),
                        List.of("중개", "보수"));
        var result = search.search(query);
        assertThat(result.dense())
                .extracting(HybridSearchService.Candidate::chunkId)
                .containsExactlyElementsOf(current.chunkIds());
        assertThat(result.dense().get(0).channelScore()).isCloseTo(0, within(1e-9));
        assertThat(result.dense().get(1).channelScore()).isCloseTo(1, within(1e-9));
        assertThat(result.lexical())
                .extracting(HybridSearchService.Candidate::chunkId)
                .containsExactly(current.chunkIds().get(1), current.chunkIds().get(0));
        var fusedExpected = new ArrayList<>(current.chunkIds().subList(0, 2));
        fusedExpected.sort(Comparator.comparing(UUID::toString));
        fusedExpected.add(current.chunkIds().get(2));
        assertThat(result.fused())
                .extracting(ReciprocalRankFusion.Ranked::chunkId)
                .containsExactlyElementsOf(fusedExpected);
        assertThat(result.lawVersionIds()).containsExactly(current.versionId());
        var past =
                search.search(
                        new HybridSearchService.Query(
                                Set.of(lawId),
                                LocalDate.of(2024, 12, 31),
                                model,
                                tokenizer,
                                LawImportService.CHUNKER,
                                vector(1, 0),
                                List.of("보수")));
        assertThat(past.dense())
                .extracting(HybridSearchService.Candidate::chunkId)
                .containsExactly(old.chunkIds().get(0));
        assertThat(
                        search.search(
                                        new HybridSearchService.Query(
                                                Set.of(lawId),
                                                LocalDate.of(2023, 1, 1),
                                                model,
                                                tokenizer,
                                                LawImportService.CHUNKER,
                                                vector(1, 0),
                                                List.of("보수")))
                                .availability())
                .isEqualTo("LAW_VERSION_UNAVAILABLE");
        var mismatch =
                search.search(
                        new HybridSearchService.Query(
                                Set.of(lawId),
                                query.referenceDate(),
                                model,
                                "fixture-tokenizer-v2",
                                LawImportService.CHUNKER,
                                vector(1, 0),
                                List.of("보수")));
        assertThat(mismatch.fused()).isEmpty();
        jdbc.update(
                "UPDATE law_chunk_indexes SET source_hash=repeat('0',64) WHERE chunk_id=?",
                current.chunkIds().get(0));
        jdbc.update(
                "UPDATE law_chunk_indexes SET dense_status='FAILED',embedding=NULL WHERE chunk_id=?",
                current.chunkIds().get(1));
        var filtered = search.search(query);
        assertThat(filtered.dense())
                .extracting(HybridSearchService.Candidate::chunkId)
                .containsExactly(current.chunkIds().get(2));
        assertThat(filtered.lexical())
                .extracting(HybridSearchService.Candidate::chunkId)
                .containsExactly(current.chunkIds().get(1));
    }

    @Test
    void reviewRequiredCorpusAndInvalidSyntheticModelCannotEnterSearch() {
        var review =
                imports.importVersion(
                        law(
                                "review-" + UUID.randomUUID(),
                                "v1",
                                LocalDate.of(2024, 1, 1),
                                null,
                                "REVIEW_REQUIRED",
                                List.of("검수 대기")));
        UUID lawId =
                jdbc.queryForObject(
                        "SELECT law_id FROM law_versions WHERE id=?",
                        UUID.class,
                        review.versionId());
        assertThatThrownBy(
                        () ->
                                indexes.write(
                                        review.chunkIds().get(0),
                                        "bge-m3-real-claim",
                                        "fixture-token-v1",
                                        vector(1, 0),
                                        List.of("보수")))
                .isInstanceOf(IllegalArgumentException.class);
        indexes.write(
                review.chunkIds().get(0),
                "fixture-vector-v1",
                "fixture-token-v1",
                vector(1, 0),
                List.of());
        assertThat(
                        search.search(
                                        new HybridSearchService.Query(
                                                Set.of(lawId),
                                                LocalDate.of(2025, 1, 1),
                                                "fixture-vector-v1",
                                                "fixture-token-v1",
                                                LawImportService.CHUNKER,
                                                vector(1, 0),
                                                List.of()))
                                .availability())
                .isEqualTo("LAW_VERSION_UNAVAILABLE");
    }

    @Test
    void dbEvidenceValidationRejectsOffsetsWrongCandidateVersionAndDate() {
        var corpus =
                imports.importVersion(
                        law(
                                "evidence-" + UUID.randomUUID(),
                                "v1",
                                LocalDate.of(2024, 1, 1),
                                null,
                                "VERIFIED",
                                List.of("가😀조건\n가😀조건")));
        UUID chunk = corpus.chunkIds().get(0);
        var quote = new com.lawground.trace.service.EvidenceValidator.Quote(chunk, "😀조건", 1, 4);
        var validated =
                evidence.resolve(
                        Set.of(chunk),
                        Set.of(corpus.versionId()),
                        LocalDate.of(2025, 1, 1),
                        List.of(quote));
        assertThat(validated.get(0).sourceUrl()).isEqualTo("https://example.invalid/synthetic");
        assertThat(validated.get(0).lawTitle()).isEqualTo("합성 법령");
        assertThatThrownBy(
                        () ->
                                evidence.resolve(
                                        Set.of(),
                                        Set.of(corpus.versionId()),
                                        LocalDate.of(2025, 1, 1),
                                        List.of(quote)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                evidence.resolve(
                                        Set.of(chunk),
                                        Set.of(UUID.randomUUID()),
                                        LocalDate.of(2025, 1, 1),
                                        List.of(quote)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                evidence.resolve(
                                        Set.of(chunk),
                                        Set.of(corpus.versionId()),
                                        LocalDate.of(2023, 1, 1),
                                        List.of(quote)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                evidence.resolve(
                                        Set.of(chunk),
                                        Set.of(corpus.versionId()),
                                        LocalDate.of(2025, 1, 1),
                                        List.of(
                                                new com.lawground.trace.service.EvidenceValidator
                                                        .Quote(chunk, "😀조건", 2, 5))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void confirmedMessageWithoutDbStampAndAckBeforeCrashIsRedeliveredSafely() throws Exception {
        var accepted = trace(question("Confirm and ACK crash windows"), UUID.randomUUID());
        UUID id = job(accepted);
        waitPublished(id);
        new RabbitAdmin(template.getConnectionFactory())
                .purgeQueue(MockQueueConfiguration.QUEUE, false);
        jdbc.update(
                "UPDATE processing_jobs SET status='PENDING',last_published_at=NULL WHERE id=?",
                id);
        var correlation =
                new org.springframework.amqp.rabbit.connection.CorrelationData(
                        UUID.randomUUID().toString());
        var properties = new org.springframework.amqp.core.MessageProperties();
        properties.setContentType("application/json");
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        byte[] payload =
                mapper.writeValueAsBytes(new JobPublisher.Envelope(1, id, UUID.randomUUID()));
        template.send(
                MockQueueConfiguration.EXCHANGE,
                MockQueueConfiguration.KEY,
                new org.springframework.amqp.core.Message(payload, properties),
                correlation);
        assertThat(correlation.getFuture().get(5, TimeUnit.SECONDS).isAck()).isTrue();
        assertThat(correlation.getReturned()).isNull();
        // Simulated termination before the confirm callback's DB stamp: message exists while
        // PENDING.
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM processing_jobs WHERE id=?", String.class, id))
                .isEqualTo("PENDING");
        var factory = new com.rabbitmq.client.ConnectionFactory();
        factory.setUri(rabbit.getAmqpUrl());
        try (var connection = factory.newConnection()) {
            var first = connection.createChannel();
            var delivery = first.basicGet(MockQueueConfiguration.QUEUE, false);
            assertThat(delivery).isNotNull();
            worker.process(id);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT status FROM processing_jobs WHERE id=?",
                                    String.class,
                                    id))
                    .isEqualTo("SUCCEEDED");
            first.close(); // Final result committed, ACK omitted; broker must requeue.
            try (var second = connection.createChannel()) {
                com.rabbitmq.client.GetResponse again = null;
                long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (again == null && System.nanoTime() < end) {
                    again = second.basicGet(MockQueueConfiguration.QUEUE, false);
                    if (again == null) Thread.sleep(25);
                }
                assertThat(again).isNotNull();
                assertThat(again.getEnvelope().isRedeliver()).isTrue();
                worker.process(id);
                second.basicAck(again.getEnvelope().getDeliveryTag(), false);
            }
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT delivery_count FROM processing_jobs WHERE id=?",
                                Integer.class,
                                id))
                .isEqualTo(1);
    }

    @Test
    void mandatoryReturnDoesNotMarkUnroutableJobQueued() throws Exception {
        var accepted = trace(question("Unroutable confirm"), UUID.randomUUID());
        UUID id = job(accepted);
        waitPublished(id);
        jdbc.update(
                "UPDATE processing_jobs SET status='PENDING',last_published_at=NULL WHERE id=?",
                id);
        var correlation =
                new org.springframework.amqp.rabbit.connection.CorrelationData(
                        UUID.randomUUID().toString());
        template.send(
                MockQueueConfiguration.EXCHANGE,
                "missing-route",
                new org.springframework.amqp.core.Message(
                        mapper.writeValueAsBytes(
                                new JobPublisher.Envelope(1, id, UUID.randomUUID()))),
                correlation);
        assertThat(correlation.getFuture().get(5, TimeUnit.SECONDS).isAck()).isTrue();
        assertThat(correlation.getReturned()).isNotNull();
        jobs.published(
                id,
                jdbc.queryForObject(
                        "SELECT publish_attempts FROM processing_jobs WHERE id=?",
                        Integer.class,
                        id),
                correlation.getReturned() == null);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM processing_jobs WHERE id=?", String.class, id))
                .isEqualTo("PENDING");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT last_error_code FROM processing_jobs WHERE id=?",
                                String.class,
                                id))
                .isEqualTo("PUBLISH_FAILED");
    }

    @Test
    void failedJobInsertRollsBackAdmissionAndPublishesNothing() throws Exception {
        UUID q = question("Atomic admission rollback");
        jdbc.execute(
                "CREATE FUNCTION fail_fixture_job_insert() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF EXISTS(SELECT 1 FROM trace_runs WHERE id=NEW.run_id AND question_id='"
                        + q
                        + "'::uuid) THEN RAISE EXCEPTION 'Synthetic admission failure' USING ERRCODE='23514'; END IF; RETURN NEW; END; $$");
        jdbc.execute(
                "CREATE TRIGGER fail_fixture_job_insert BEFORE INSERT ON processing_jobs FOR EACH ROW EXECUTE FUNCTION fail_fixture_job_insert()");
        try {
            perform(
                    post("/api/v1/questions/" + q + "/trace-runs")
                            .header("Idempotency-Key", UUID.randomUUID())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"questionRevision\":1}"),
                    500);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM trace_runs WHERE question_id=?",
                                    Integer.class,
                                    q))
                    .isZero();
            assertThat(template.receive(MockQueueConfiguration.QUEUE, 100)).isNull();
        } finally {
            jdbc.execute("DROP TRIGGER fail_fixture_job_insert ON processing_jobs");
            jdbc.execute("DROP FUNCTION fail_fixture_job_insert()");
        }
    }

    @Test
    void ownerEditingReviewedPublicQuestionRemovesOfficialApproval() throws Exception {
        UUID q = question("Reviewed synthetic question");
        jdbc.update(
                "UPDATE questions SET visibility='PUBLIC',source_kind='EXAM_IMPORT',answer_source='OFFICIAL_VERIFIED',source_key=?,source_url='https://example.invalid/synthetic' WHERE id=?",
                "SYNTHETIC:" + q,
                q);
        mvc.perform(get("/api/v1/questions/" + q)).andExpect(status().isOk());
        var changed =
                perform(
                        put("/api/v1/questions/" + q)
                                .header("If-Match", "\"1\"")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        mapper.writeValueAsBytes(
                                                input("User modified synthetic question"))),
                        200);
        assertThat(changed.path("visibility").asText()).isEqualTo("PRIVATE");
        assertThat(changed.path("answerSource").asText()).isEqualTo("USER_PROVIDED");
        mvc.perform(get("/api/v1/questions/" + q)).andExpect(status().isNotFound());
    }

    @Test
    void stalePublisherCallbackCannotOverwriteNewerPublication() throws Exception {
        var accepted = trace(question("Publisher attempt generation"), UUID.randomUUID());
        UUID id = job(accepted);
        waitPublished(id);
        jdbc.update(
                "UPDATE processing_jobs SET status='PENDING',last_published_at=NULL WHERE id=?",
                id);
        int old = jobs.publicationAttempt(id), current = jobs.publicationAttempt(id);
        assertThat(current).isGreaterThan(old);
        jobs.published(id, current, true);
        jobs.published(id, old, false);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT last_error_code FROM processing_jobs WHERE id=?",
                                String.class,
                                id))
                .isNull();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM processing_jobs WHERE id=?", String.class, id))
                .isEqualTo("QUEUED");
    }
}
