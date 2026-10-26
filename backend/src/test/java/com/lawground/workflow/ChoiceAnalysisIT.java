package com.lawground.workflow;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lawground.integration.ai.support.VerifierTestServer;
import com.lawground.job.service.*;
import com.lawground.law.dto.LawImport;
import com.lawground.law.search.LiveLawIndexService;
import com.lawground.law.service.LawImportService;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;

/** Synthetic verifier labels; real DB/queue/HTTP and public result contract. */
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
class ChoiceAnalysisIT {
    static final UUID LAW = UUID.randomUUID();
    static final String SOURCE = "alpha 앞😀근거 문장\n뒤";
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

    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired LawImportService imports;
    @Autowired LiveLawIndexService indexes;
    @Autowired LiveJobWorker worker;
    @Autowired JobStore jobs;
    UUID owner, other;

    @BeforeEach
    void setup() {
        HTTP.reset();
        owner = UUID.randomUUID();
        other = UUID.randomUUID();
        for (var id : List.of(owner, other))
            jdbc.update(
                    "INSERT INTO members(id,display_name,status) VALUES (?,'Synthetic choice actor','ACTIVE')",
                    id);
        jdbc.update(
                "INSERT INTO laws(id,source_provider,source_law_id,title) VALUES (?,'LAW_GO_KR','SYNTHETIC-CHOICE','SYNTHETIC') ON CONFLICT DO NOTHING",
                LAW);
        var imported =
                imports.importVersion(
                        new LawImport(
                                "LAW_GO_KR",
                                "SYNTHETIC-CHOICE",
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
        indexes.indexChunk(imported.chunkIds().getFirst());
        HTTP.reset();
        HTTP.transform = this::choices;
    }

    @AfterEach
    void check() {
        assertThat(HTTP.violations).isEmpty();
    }

    @AfterAll
    static void close() {
        HTTP.close();
    }

    ObjectNode choices(ObjectNode out) {
        var evidence = out.withArray("evidences");
        var first = evidence.get(0);
        evidence.addObject()
                .put("evidenceKey", "e2")
                .put("chunkId", first.path("chunkId").asText())
                .put("quote", "alpha")
                .put("start", 0)
                .put("end", 5);
        var list = out.putArray("choiceAnalyses");
        for (int i = 1; i <= 5; i++) {
            var c =
                    list.addObject()
                            .put("choiceNumber", i)
                            .put(
                                    "relation",
                                    i == 2
                                            ? "CONTRADICTS"
                                            : i == 3 || i == 5 ? "INSUFFICIENT" : "SUPPORTS")
                            .put("explanation", "합성 선지 설명");
            if (i == 2) c.put("correctedText", "합성 정정문");
            else c.putNull("correctedText");
            c.putArray("tags");
            var keys = c.putArray("evidenceKeys");
            if (i != 3) keys.add(i == 4 ? "e2" : "e1");
            if (i == 2) keys.add("e2");
        }
        return out;
    }

    ObjectNode input(String stem) {
        var q =
                json.createObjectNode()
                        .put("subject", "BROKER_LAW")
                        .put("stem", stem)
                        .put("questionType", "SELECT_INCORRECT")
                        .put("providedAnswerNumber", 2)
                        .put("referenceDate", "2025-10-25");
        var c = q.putArray("choices");
        for (int i = 1; i <= 5; i++) c.addObject().put("number", i).put("text", "원래 선지 😀 " + i);
        return q;
    }

    void capture(String contract, String schema, JsonNode body) throws Exception {
        var folder = Path.of("build/contract-responses");
        Files.createDirectories(folder);
        json.writeValue(
                folder.resolve(UUID.randomUUID() + ".json").toFile(),
                Map.of("contract", contract, "schema", schema, "body", body));
    }

    JsonNode api(MockHttpServletRequestBuilder request, int statusCode, String schema)
            throws Exception {
        var response =
                mvc.perform(request.with(user(owner.toString())).with(csrf()))
                        .andExpect(status().is(statusCode))
                        .andReturn()
                        .getResponse();
        if (response.getContentAsByteArray().length == 0) return null;
        var body = json.readTree(response.getContentAsByteArray());
        capture("external", schema, body);
        return body;
    }

    record Run(UUID question, UUID run, UUID job) {}

    Run submit() throws Exception {
        var q =
                api(
                        post("/api/v1/questions")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsBytes(
                                                input("합성 문제 " + UUID.randomUUID()))),
                        201,
                        "Question");
        var accepted =
                api(
                        post("/api/v1/questions/" + q.path("id").asText() + "/trace-runs")
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("Idempotency-Key", UUID.randomUUID())
                                .content("{\"questionRevision\":1}"),
                        202,
                        "TraceAccepted");
        return new Run(
                UUID.fromString(q.path("id").asText()),
                UUID.fromString(accepted.path("runId").asText()),
                UUID.fromString(accepted.path("jobId").asText()));
    }

    JsonNode result(Run r) throws Exception {
        return api(get("/api/v1/trace-runs/" + r.run() + "/result"), 200, "Result");
    }

    int count(String table, UUID run) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE run_id=?", Integer.class, run);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUPPORTED", "REJECTED"})
    void mapsFiveFrozenChoicesAndMultipleEvidenceKeysToStoredUuids(String verdict)
            throws Exception {
        HTTP.transform = n -> choices(n).put("verdict", verdict);
        var r = submit();
        worker.process(r.job());
        var body = result(r);
        var list = body.path("choiceAnalyses");
        assertThat(list.size()).isEqualTo(5);
        assertThat(body.path("verdict").asText()).isEqualTo(verdict);
        assertThat(count("choice_analyses", r.run())).isEqualTo(5);
        assertThat(count("choice_analysis_evidences", r.run())).isEqualTo(5);
        assertThat(list.get(1).path("evidenceIds").size()).isEqualTo(2);
        assertThat(list.get(1).path("originalText").asText()).isEqualTo("원래 선지 😀 2");
        assertThat(list.get(2).path("relation").asText()).isEqualTo("INSUFFICIENT");
        assertThat(list.get(2).path("correctedText").isNull()).isTrue();
        Set<String> ids = new HashSet<>();
        body.path("evidences").forEach(e -> ids.add(e.path("evidenceId").asText()));
        list.forEach(
                c -> c.path("evidenceIds").forEach(id -> assertThat(ids).contains(id.asText())));
        var checkpoint =
                json.readTree(
                        jdbc.queryForObject(
                                "SELECT checkpoint::text FROM trace_runs WHERE id=?",
                                String.class,
                                r.run()));
        capture("internal", "VerifyOutput", checkpoint.at("/verification/result"));
        capture("internal", "VerifyInput", HTTP.verifyRequests.getFirst());
        int calls = HTTP.posts.size();
        worker.process(r.job());
        assertThat(result(r)).isEqualTo(body);
        assertThat(HTTP.posts).hasSize(calls);
        mvc.perform(get("/api/v1/trace-runs/" + r.run() + "/result").with(user(other.toString())))
                .andExpect(status().isNotFound());
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE choice_analyses SET explanation='changed' WHERE run_id=?",
                                        r.run()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "DELETE FROM choice_analysis_evidences WHERE run_id=?",
                                        r.run()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE trace_runs SET summary='changed' WHERE id=?",
                                        r.run()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void insufficientChoicesNeedNoInventedEvidenceOrCorrection() throws Exception {
        HTTP.transform =
                n -> {
                    choices(n);
                    n.put("verdict", "HOLD");
                    n.putArray("evidences");
                    n.withArray("choiceAnalyses")
                            .forEach(
                                    c -> {
                                        ((ObjectNode) c)
                                                .put("relation", "INSUFFICIENT")
                                                .putNull("correctedText")
                                                .putArray("evidenceKeys");
                                    });
                    return n;
                };
        var r = submit();
        worker.process(r.job());
        var out = result(r);
        assertThat(out.path("evidences").isEmpty()).isTrue();
        assertThat(out.path("choiceAnalyses").size()).isEqualTo(5);
        assertThat(count("choice_analysis_evidences", r.run())).isZero();
    }

    @Test
    void usesRunSnapshotAfterQuestionRevisionChanges() throws Exception {
        var r = submit();
        var changed = input("수정된 문제 " + UUID.randomUUID());
        changed.withArray("choices").forEach(c -> ((ObjectNode) c).put("text", "새로운 선지"));
        api(
                put("/api/v1/questions/" + r.question())
                        .header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(changed)),
                200,
                "Question");
        worker.process(r.job());
        assertThat(result(r).path("choiceAnalyses").get(0).path("originalText").asText())
                .isEqualTo("원래 선지 😀 1");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "unknown-evidence",
                "missing",
                "type",
                "unknown-field",
                "tag",
                "version",
                "unsupported-correction"
            })
    void invalidExtensionsFailBeforeAnyFinalRows(String kind) throws Exception {
        HTTP.transform =
                n -> {
                    choices(n);
                    var first = (ObjectNode) n.withArray("choiceAnalyses").get(0);
                    switch (kind) {
                        case "unknown-evidence" -> first.putArray("evidenceKeys").add("not-known");
                        case "missing" -> n.withArray("choiceAnalyses").remove(4);
                        case "type" -> first.put("choiceNumber", "1");
                        case "unknown-field" -> first.put("extra", true);
                        case "tag" -> first.putArray("tags").addObject().put("code", "NOT_AGREED");
                        case "version" -> n.put("tagSetVersion", "unagreed-v1");
                        case "unsupported-correction" -> first.put("correctedText", "unsupported");
                        default -> throw new AssertionError(kind);
                    }
                    return n;
                };
        var r = submit();
        worker.process(r.job());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM processing_jobs WHERE id=?",
                                String.class,
                                r.job()))
                .isEqualTo("FAILED");
        assertThat(count("choice_analyses", r.run())).isZero();
        assertThat(count("evidence_results", r.run())).isZero();
        api(get("/api/v1/trace-runs/" + r.run() + "/result"), 409, "Error");
    }

    @ParameterizedTest
    @ValueSource(strings = {"none", "number", "explanation"})
    void rollbackKeepsVerificationCheckpointAndResumeDoesNotRecallModels(String corrupt)
            throws Exception {
        var r = submit();
        jdbc.execute(
                "CREATE FUNCTION synthetic_reject_choice() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.choice_number=4 THEN RAISE EXCEPTION 'synthetic failure' USING ERRCODE='23514'; END IF; RETURN NEW; END; $$");
        jdbc.execute(
                "CREATE TRIGGER synthetic_choice_failure BEFORE INSERT ON choice_analyses FOR EACH ROW EXECUTE FUNCTION synthetic_reject_choice()");
        try {
            assertThatThrownBy(() -> worker.process(r.job()))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(count("choice_analyses", r.run())).isZero();
            assertThat(count("evidence_results", r.run())).isZero();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT checkpoint->>'VERIFY' FROM trace_runs WHERE id=?",
                                    String.class,
                                    r.run()))
                    .isEqualTo("true");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT status FROM processing_jobs WHERE id=?",
                                    String.class,
                                    r.job()))
                    .isEqualTo("RUNNING");
        } finally {
            jdbc.execute("DROP TRIGGER synthetic_choice_failure ON choice_analyses");
            jdbc.execute("DROP FUNCTION synthetic_reject_choice()");
        }
        int calls = HTTP.posts.size();
        if (!corrupt.equals("none"))
            jdbc.update(
                    "UPDATE trace_runs SET checkpoint=jsonb_set(checkpoint,?::text[],?::jsonb) WHERE id=?",
                    corrupt.equals("number")
                            ? "{verification,result,choiceAnalyses,0,choiceNumber}"
                            : "{verification,result,choiceAnalyses,0,explanation}",
                    corrupt.equals("number") ? "\"1\"" : "99",
                    r.run());
        jdbc.update(
                "UPDATE processing_jobs SET lease_until=now()-interval '1 second' WHERE id=?",
                r.job());
        worker.process(r.job());
        if (corrupt.equals("none"))
            assertThat(result(r).path("choiceAnalyses").size()).isEqualTo(5);
        else {
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT reason_code FROM trace_runs WHERE id=?",
                                    String.class,
                                    r.run()))
                    .isEqualTo("CHECKPOINT_INVALID");
            assertThat(count("choice_analyses", r.run())).isZero();
            assertThat(count("evidence_results", r.run())).isZero();
        }
        assertThat(HTTP.posts).hasSize(calls);
    }

    @Test
    void databaseRejectsPartialChoicesAndCrossRunEvidenceLinks() throws Exception {
        var complete = submit();
        worker.process(complete.job());
        var pending = submit();
        assertThat(jobs.claim(pending.job(), "synthetic", "LIVE")).isNotNull();
        jdbc.update(
                "UPDATE trace_runs SET checkpoint=jsonb_set(checkpoint,'{VERIFY}','true'::jsonb) WHERE id=?",
                pending.run());
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO choice_analyses(id,run_id,choice_number,original_text,relation,explanation,tags) VALUES (?,?,1,'원래 선지 😀 1','INSUFFICIENT','합성 설명','[]'::jsonb)",
                                        UUID.randomUUID(),
                                        pending.run()))
                .isInstanceOf(DataIntegrityViolationException.class);
        var choice =
                jdbc.queryForObject(
                        "SELECT id FROM choice_analyses WHERE run_id=? AND choice_number=1",
                        UUID.class,
                        complete.run());
        var evidence =
                jdbc.queryForObject(
                        "SELECT id FROM evidence_results WHERE run_id=? AND ordinal=0",
                        UUID.class,
                        complete.run());
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO choice_analysis_evidences(run_id,choice_analysis_id,evidence_id) VALUES (?,?,?)",
                                        pending.run(),
                                        choice,
                                        evidence))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(count("choice_analyses", pending.run())).isZero();
    }

    @Test
    void cancellationBeforeProcessingStoresNoChoiceRows() throws Exception {
        var r = submit();
        api(post("/api/v1/trace-runs/" + r.run() + "/cancel"), 202, "CancelAccepted");
        worker.process(r.job());
        assertThat(count("choice_analyses", r.run())).isZero();
        assertThat(HTTP.posts).isEmpty();
    }

    @Test
    void upgradesV007WithoutChangingChecksumsOrMembers() {
        var ds =
                new org.springframework.jdbc.datasource.DriverManagerDataSource(
                        pg.getJdbcUrl()
                                + (pg.getJdbcUrl().contains("?") ? "&" : "?")
                                + "currentSchema=choice_upgrade",
                        pg.getUsername(),
                        pg.getPassword());
        var base =
                org.flywaydb.core.Flyway.configure().dataSource(ds).defaultSchema("choice_upgrade");
        base.target("7").load().migrate();
        var sql = new JdbcTemplate(ds);
        var before =
                sql.queryForList(
                        "SELECT version,checksum FROM flyway_schema_history WHERE type='SQL' ORDER BY installed_rank");
        UUID id = UUID.randomUUID();
        sql.update(
                "INSERT INTO members(id,display_name,status) VALUES (?,'upgrade preservation','ACTIVE')",
                id);
        base.target("latest").load().migrate();
        assertThat(
                        sql.queryForList(
                                "SELECT version,checksum FROM flyway_schema_history WHERE type='SQL' AND installed_rank<=7 ORDER BY installed_rank"))
                .isEqualTo(before);
        assertThat(
                        sql.queryForObject(
                                "SELECT count(*) FROM flyway_schema_history WHERE type='SQL'",
                                Integer.class))
                .isEqualTo(8);
        assertThat(
                        sql.queryForObject(
                                "SELECT display_name FROM members WHERE id=?", String.class, id))
                .isEqualTo("upgrade preservation");
    }
}
