package com.lawground.recommendation;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;
import com.lawground.global.config.JsonData;
import com.lawground.global.error.ApiException;
import com.lawground.integration.ai.client.AiClientException;
import com.lawground.integration.ai.dto.AiDtos.*;
import com.lawground.job.service.LiveRunPlan;
import com.lawground.law.dto.LawImport;
import com.lawground.law.service.LawImportService;
import com.lawground.question.dto.*;
import com.lawground.question.service.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.*;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** Real PostgreSQL/cosine and HTTP, synthetic questions, official flags and verified traces. */
@SpringBootTest(
        properties = {
            "lawground.analysis.listener-enabled=false",
            "lawground.analysis.recovery-delay-ms=3600000"
        })
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class RecommendationIT {
    static final String MODEL = "a".repeat(40), KEY = "synthetic-question-embed-token";
    static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    static final HttpServer HTTP = server();
    static final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    static final List<String> violations = new CopyOnWriteArrayList<>();
    static volatile Runnable onEmbed = () -> {};
    static volatile boolean badVector, off;

    @Container @ServiceConnection
    static final PostgreSQLContainer<?> pg =
            new PostgreSQLContainer<>(
                    DockerImageName.parse("pgvector/pgvector:0.8.2-pg16")
                            .asCompatibleSubstituteFor("postgres"));

    @Container @ServiceConnection
    static final RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:4.2.9-management");

    @DynamicPropertySource
    static void settings(DynamicPropertyRegistry r) {
        r.add(
                "lawground.ai.base-url",
                () -> "http://127.0.0.1:" + HTTP.getAddress().getPort() + "/internal/v1");
        r.add("lawground.ai.api-key", () -> KEY);
        r.add("lawground.ai.embedding-model-revision", () -> MODEL);
    }

    static HttpServer server() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(
                    Executors.newCachedThreadPool(
                            r -> {
                                var t = new Thread(r);
                                t.setDaemon(true);
                                return t;
                            }));
            server.createContext(
                    "/internal/v1/",
                    x -> {
                        if (!KEY.equals(x.getRequestHeaders().getFirst("X-Internal-Token")))
                            violations.add("token");
                        if (!"0.1.0-draft"
                                .equals(x.getRequestHeaders().getFirst("X-Contract-Version")))
                            violations.add("contract");
                        Object out;
                        int status = 200;
                        if (x.getRequestURI().getPath().endsWith("health/ready")) {
                            status = off ? 503 : 200;
                            out =
                                    new ReadyOutput(
                                            off ? ReadyStatus.NOT_READY : ReadyStatus.READY,
                                            List.of(
                                                    new ModelState(
                                                            "embed:BAAI/bge-m3", MODEL, !off)));
                        } else {
                            var in = JSON.readTree(x.getRequestBody());
                            requests.add(in);
                            if (!in.path("requestId")
                                    .asText()
                                    .equals(x.getRequestHeaders().getFirst("X-Request-Id")))
                                violations.add("request-id");
                            onEmbed.run();
                            var vector = new ArrayList<>(Collections.nCopies(1024, 0.0));
                            vector.set(0, badVector ? 0.0 : 1.0);
                            out =
                                    new EmbedResponse(
                                            List.of(
                                                    new EmbedItem(
                                                            UUID.fromString(
                                                                    in.at("/texts/0/id").asText()),
                                                            vector)),
                                            1024,
                                            MODEL);
                        }
                        var bytes = JSON.writeValueAsBytes(out);
                        x.getResponseHeaders().set("Content-Type", "application/json");
                        x.sendResponseHeaders(status, bytes.length);
                        x.getResponseBody().write(bytes);
                        x.close();
                    });
            server.start();
            return server;
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @AfterAll
    static void stop() {
        HTTP.stop(0);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired QuestionService questions;
    @Autowired QuestionLibraryService library;
    @Autowired QuestionEmbeddingStore embeddings;
    @Autowired QuestionEmbeddingService indexing;
    @Autowired RecommendationService recommendations;
    @Autowired LawImportService imports;
    @Autowired PlatformTransactionManager tx;
    @Autowired MockMvc mvc;
    UUID owner, other, article, chunk, source, run;

    @BeforeEach
    void setup() throws Exception {
        requests.clear();
        violations.clear();
        off = false;
        badVector = false;
        onEmbed = () -> {};
        jdbc.update("UPDATE questions SET deleted_at=now() WHERE deleted_at IS NULL");
        owner = UUID.randomUUID();
        other = UUID.randomUUID();
        for (var id : List.of(owner, other))
            jdbc.update(
                    "INSERT INTO members(id,display_name,status) VALUES (?,'Synthetic recommendation actor','ACTIVE')",
                    id);
        var law =
                imports.importVersion(
                        new LawImport(
                                "LAW_GO_KR",
                                "SYNTHETIC-REC-" + UUID.randomUUID(),
                                "v1",
                                "SYNTHETIC ONLY",
                                LocalDate.of(2024, 1, 1),
                                LocalDate.of(2026, 1, 1),
                                "VERIFIED",
                                "https://www.law.go.kr/TEST-ONLY",
                                Map.of("synthetic", true),
                                List.of(
                                        new LawImport.Article(
                                                "a1",
                                                "TEST",
                                                "합성 원문",
                                                List.of(new LawImport.Span(0, 5, "합성 원문"))))));
        article = law.articleIds().getFirst();
        chunk = law.chunkIds().getFirst();
        source = question(owner, "2025-10-25");
        run = trace(source, owner, "USER", "LIVE", "SUPPORTED", true);
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        assertThat(violations).isEmpty();
    }

    <T> T actor(UUID id, Callable<T> work) throws Exception {
        var previous = SecurityContextHolder.getContext();
        var c = SecurityContextHolder.createEmptyContext();
        c.setAuthentication(
                new UsernamePasswordAuthenticationToken(id.toString(), null, List.of()));
        SecurityContextHolder.setContext(c);
        try {
            return work.call();
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }

    CreateQuestionRequest input(String stem, String date) {
        return new CreateQuestionRequest(
                CreateQuestionRequest.Subject.BROKER_LAW,
                stem,
                CreateQuestionRequest.QuestionType.SELECT_INCORRECT,
                java.util.stream.IntStream.rangeClosed(1, 5)
                        .mapToObj(i -> new CreateQuestionRequest.Choice(i, "합성 선지 " + i))
                        .toList(),
                2,
                LocalDate.parse(date));
    }

    UUID question(UUID actor, String date) throws Exception {
        return actor(
                actor,
                () ->
                        questions
                                .create(input("SYNTHETIC 문제 " + UUID.randomUUID() + " 😀", date))
                                .id());
    }

    void reviewedPublic(UUID id) {
        jdbc.update(
                "UPDATE questions SET visibility='PUBLIC',source_kind='EXAM_IMPORT',answer_source='OFFICIAL_VERIFIED',source_key=?,source_url='https://example.test/synthetic' WHERE id=?",
                id.toString(),
                id);
    }

    UUID trace(UUID q, UUID actor, String scope, String mode, String verdict, boolean evidence)
            throws Exception {
        var problem = actor(actor, () -> questions.get(q));
        var snapshot = json.writeValueAsString(QuestionSnapshots.from(problem));
        UUID id = UUID.randomUUID();
        UUID batchId = null;
        if (scope.equals("BATCH")) {
            batchId = UUID.randomUUID();
            jdbc.update(
                    "INSERT INTO batch_runs(id,requested_by,request_hash,status,config_snapshot) VALUES (?,?,?,'RUNNING','{}'::jsonb)",
                    batchId,
                    actor,
                    "a".repeat(64));
            jdbc.update(
                    "INSERT INTO batch_items(batch_run_id,question_id,question_revision,input_snapshot,input_hash,status) VALUES (?,?,?,?::jsonb,?,'PENDING')",
                    batchId,
                    q,
                    problem.revision(),
                    snapshot,
                    JsonData.hash(snapshot));
        }
        var versions = new LinkedHashMap<String, Object>();
        versions.put("pipelineVersion", LiveRunPlan.VERIFIED_PIPELINE);
        versions.put("embeddingModelRevision", MODEL);
        jdbc.update(
                "INSERT INTO trace_runs(id,question_id,requester_id,question_revision,input_snapshot,input_hash,reference_date,operation,idempotency_key,scope,execution_mode,status,verdict,pipeline_version,config_snapshot,versions,created_at,completed_at,batch_run_id) VALUES (?,?,?,?,?::jsonb,?,?,?, ?,?,?,'COMPLETED',?,?, '{}'::jsonb,?::jsonb,now(),now(),?)",
                id,
                q,
                actor,
                problem.revision(),
                snapshot,
                JsonData.hash(snapshot),
                problem.referenceDate(),
                scope.equals("BATCH") ? "BATCH_TRACE_CREATE" : "TRACE_CREATE",
                UUID.randomUUID(),
                scope,
                mode,
                verdict,
                LiveRunPlan.VERIFIED_PIPELINE,
                json.writeValueAsString(versions),
                batchId);
        if (evidence)
            jdbc.update(
                    "INSERT INTO evidence_results(id,run_id,chunk_id,ordinal,quote,start_offset,end_offset,metadata_snapshot) VALUES (?,?,?,0,'합성 원문',0,5,'{}'::jsonb)",
                    UUID.randomUUID(),
                    id,
                    chunk);
        return id;
    }

    UUID own(boolean shared) throws Exception {
        var q = question(owner, "2025-10-25");
        trace(q, owner, "USER", "LIVE", "SUPPORTED", shared);
        return q;
    }

    void vector(UUID q, UUID actor, double score) throws Exception {
        var s = actor(actor, () -> embeddings.load(q, MODEL));
        float[] v = new float[1024];
        v[0] = (float) score;
        v[1] = (float) Math.sqrt(1 - score * score);
        actor(actor, () -> embeddings.publish(s, v));
    }

    List<RecommendationService.Item> list() throws Exception {
        return actor(owner, () -> recommendations.list(run, 10)).items();
    }

    JsonNode get(UUID actor, String path, int expected) throws Exception {
        var request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path);
        if (actor != null) request.with(user(actor.toString()));
        var response =
                mvc.perform(request).andExpect(status().is(expected)).andReturn().getResponse();
        var body = json.readTree(response.getContentAsByteArray());
        var folder = Path.of("build/contract-responses");
        Files.createDirectories(folder);
        json.writeValue(
                folder.resolve(UUID.randomUUID() + ".json").toFile(),
                Map.of(
                        "contract",
                        "external",
                        "schema",
                        expected == 200 ? "RecommendationList" : "Error",
                        "body",
                        body));
        return body;
    }

    String path() {
        return "/api/v1/trace-runs/" + run + "/recommendations";
    }

    @Test
    void sharedEvidenceRanksFirstThenCosineAndStableUuidWithoutCallingModels() throws Exception {
        var shared = own(true);
        var similarity = question(other, "2025-10-25");
        reviewedPublic(similarity);
        vector(source, owner, 1);
        vector(shared, owner, 0.7);
        vector(similarity, other, 1);
        var duplicate = trace(shared, owner, "USER", "LIVE", "SUPPORTED", true);
        var items = list();
        assertThat(items)
                .extracting(RecommendationService.Item::questionId)
                .containsExactly(shared, similarity);
        assertThat(items.getFirst().sharedArticleIds()).containsExactly(article);
        assertThat(items.getFirst().similarity()).isCloseTo(0.7, within(0.00001));
        assertThat(items.getLast().sharedArticleIds()).isEmpty();
        assertThat(items.getLast().similarity()).isEqualTo(1);
        assertThat(
                        get(owner, path() + "?limit=1", 200)
                                .path("items")
                                .get(0)
                                .path("questionId")
                                .asText())
                .isEqualTo(shared.toString());
        assertThat(get(owner, path(), 200).path("nextCursor").isNull()).isTrue();
        assertThat(requests).isEmpty();
    }

    @Test
    void emptyOrStaleSourceVectorNeverBecomesPerfectSimilarity() throws Exception {
        var shared = own(true);
        var candidate = question(other, "2025-10-25");
        reviewedPublic(candidate);
        vector(candidate, other, 1);
        assertThat(list())
                .extracting(RecommendationService.Item::questionId)
                .containsExactly(shared);
        assertThat(list().getFirst().similarity()).isNull();
        vector(source, owner, 1);
        jdbc.update(
                "UPDATE question_embeddings SET input_hash=? WHERE question_id=?",
                "0".repeat(64),
                source);
        assertThat(list())
                .extracting(RecommendationService.Item::questionId)
                .containsExactly(shared);
        assertThat(list().getFirst().similarity()).isNull();
    }

    @Test
    void privateDeletedUnreviewedOldRevisionAndOtherMembersTracesAreExcluded() throws Exception {
        vector(source, owner, 1);
        var privateQ = question(other, "2025-10-25");
        vector(privateQ, other, 1);
        var unreviewed = question(owner, "2025-10-25");
        vector(unreviewed, owner, 1);
        var deleted = own(true);
        jdbc.update("UPDATE questions SET deleted_at=now() WHERE id=?", deleted);
        var old = own(true);
        vector(old, owner, 1);
        actor(owner, () -> library.update(old, 1, input("수정 " + UUID.randomUUID(), "2025-10-25")));
        var pub = question(other, "2025-10-25");
        reviewedPublic(pub);
        trace(pub, other, "USER", "LIVE", "SUPPORTED", true);
        var eval = question(owner, "2025-10-25");
        trace(eval, owner, "EVALUATION", "LIVE", "SUPPORTED", true);
        vector(eval, owner, 1);
        var batch = question(owner, "2025-10-25");
        trace(batch, owner, "BATCH", "LIVE", "SUPPORTED", true);
        vector(batch, owner, 1);
        assertThat(list()).isEmpty();
        get(owner, path(), 200);
    }

    @Test
    void sourceModesUnfinishedStaleRevisionAndOwnerBoundaries() throws Exception {
        own(true);
        get(other, path(), 404);
        get(null, path(), 401);
        get(owner, "/api/v1/trace-runs/not-uuid/recommendations", 400);
        var invisible = trace(source, owner, "EVALUATION", "LIVE", "SUPPORTED", true);
        get(owner, "/api/v1/trace-runs/" + invisible + "/recommendations", 404);
        for (String verdict : List.of("HOLD", "REJECTED")) {
            jdbc.update("UPDATE trace_runs SET verdict=? WHERE id=?", verdict, run);
            assertThat(list()).isEmpty();
        }
        jdbc.update(
                "UPDATE trace_runs SET verdict='SUPPORTED',execution_mode='MOCK' WHERE id=?", run);
        assertThat(list()).isEmpty();
        jdbc.update(
                "UPDATE trace_runs SET execution_mode='LIVE',status='QUEUED',verdict=NULL,completed_at=NULL WHERE id=?",
                run);
        get(owner, path(), 409);
        jdbc.update(
                "UPDATE trace_runs SET status='COMPLETED',verdict='SUPPORTED',completed_at=now() WHERE id=?",
                run);
        actor(
                owner,
                () -> library.update(source, 1, input("새 문제 " + UUID.randomUUID(), "2025-10-25")));
        get(owner, path(), 409);
        jdbc.update("UPDATE members SET status='DEACTIVATED' WHERE id=?", owner);
        get(owner, path(), 403);
        jdbc.update("UPDATE members SET status='ACTIVE' WHERE id=?", owner);
        jdbc.update("UPDATE questions SET deleted_at=now() WHERE id=?", source);
        get(owner, path(), 404);
    }

    @Test
    void dateBoundariesAndUnreviewedLawPreventCrossVersionRecommendations() throws Exception {
        for (String date : List.of("2023-12-31", "2026-01-01")) {
            var q = question(other, date);
            reviewedPublic(q);
            vector(q, other, 1);
        }
        var from = question(other, "2024-01-01");
        reviewedPublic(from);
        vector(from, other, 1);
        vector(source, owner, 1);
        assertThat(list()).extracting(RecommendationService.Item::questionId).containsExactly(from);
        jdbc.update(
                "UPDATE law_versions SET applicability_status='REVIEW_REQUIRED' WHERE id=(SELECT law_version_id FROM law_articles WHERE id=?)",
                article);
        assertThat(list()).isEmpty();
    }

    @Test
    void modelHashReadyStatusThresholdAndTieOrderingAreEnforced() throws Exception {
        vector(source, owner, 1);
        var a = question(other, "2025-10-25");
        var b = question(other, "2025-10-25");
        var low = question(other, "2025-10-25");
        for (var q : List.of(a, b, low)) reviewedPublic(q);
        vector(a, other, 0.9);
        vector(b, other, 0.9);
        vector(low, other, 0.6);
        var sorted = List.of(a, b).stream().sorted(Comparator.comparing(UUID::toString)).toList();
        assertThat(list())
                .extracting(RecommendationService.Item::questionId)
                .containsExactlyElementsOf(sorted);
        jdbc.update(
                "UPDATE question_embeddings SET embedding_model_revision=? WHERE question_id=?",
                "f".repeat(40),
                a);
        jdbc.update(
                "UPDATE question_embeddings SET input_hash=? WHERE question_id=?",
                "0".repeat(64),
                b);
        assertThat(list()).isEmpty();
        jdbc.update(
                "UPDATE question_embeddings SET embedding_model_revision=? WHERE question_id=?",
                MODEL,
                a);
        jdbc.update(
                "UPDATE question_embeddings SET status='FAILED',embedding=NULL WHERE question_id=?",
                a);
        assertThat(list()).isEmpty();
    }

    @Test
    void limitsAreBoundedAndDefaultIsFive() throws Exception {
        for (int i = 0; i < 7; i++) own(true);
        assertThat(get(owner, path(), 200).path("items").size()).isEqualTo(5);
        assertThat(get(owner, path() + "?limit=10", 200).path("items").size()).isEqualTo(7);
        for (String n : List.of("0", "11", "01", "-1", "1.0", "x"))
            get(owner, path() + "?limit=" + n, 400);
    }

    @Test
    void indexingUsesCanonicalInputReusesReadyAndPublishesCurrentRevision() throws Exception {
        assertThat(actor(owner, () -> indexing.index(source)))
                .isEqualTo(QuestionEmbeddingService.Outcome.INDEXED);
        var s = actor(owner, () -> embeddings.load(source, MODEL));
        assertThat(s.ready()).isTrue();
        assertThat(s.hash()).isEqualTo(JsonData.hash(s.input()));
        assertThat(s.input()).doesNotContain("providedAnswer", "referenceDate", owner.toString());
        assertThat(requests.getFirst().at("/texts/0/text").asText()).isEqualTo(s.input());
        assertThat(actor(owner, () -> indexing.index(source)))
                .isEqualTo(QuestionEmbeddingService.Outcome.REUSED);
        assertThat(requests).hasSize(1);
        actor(
                owner,
                () -> library.update(source, 1, input("개정 입력 " + UUID.randomUUID(), "2025-10-25")));
        assertThat(actor(owner, () -> indexing.index(source)))
                .isEqualTo(QuestionEmbeddingService.Outcome.INDEXED);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM question_embeddings WHERE question_id=?",
                                Integer.class,
                                source))
                .isEqualTo(2);
    }

    @Test
    void staleDeletedRevokedAndPrivateIndexWritesAreRejected() throws Exception {
        assertThatThrownBy(() -> actor(other, () -> indexing.index(source)))
                .isInstanceOf(ApiException.class);
        assertThat(requests).isEmpty();
        onEmbed =
                () ->
                        jdbc.update(
                                "UPDATE questions SET revision=revision+1,stem='changed during HTTP' WHERE id=?",
                                source);
        assertThatThrownBy(() -> actor(owner, () -> indexing.index(source)))
                .isInstanceOf(ApiException.class);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM question_embeddings WHERE question_id=?",
                                Integer.class,
                                source))
                .isZero();
        onEmbed = () -> jdbc.update("UPDATE questions SET deleted_at=now() WHERE id=?", source);
        assertThatThrownBy(() -> actor(owner, () -> indexing.index(source)))
                .isInstanceOf(ApiException.class);
        var publicQ = question(other, "2025-10-25");
        reviewedPublic(publicQ);
        onEmbed =
                () -> jdbc.update("UPDATE questions SET visibility='PRIVATE' WHERE id=?", publicQ);
        assertThatThrownBy(() -> actor(owner, () -> indexing.index(publicQ)))
                .isInstanceOf(ApiException.class);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM question_embeddings WHERE question_id=?",
                                Integer.class,
                                publicQ))
                .isZero();
        var q = question(owner, "2025-10-25");
        onEmbed = () -> jdbc.update("UPDATE members SET status='DEACTIVATED' WHERE id=?", owner);
        assertThatThrownBy(() -> actor(owner, () -> indexing.index(q)))
                .isInstanceOf(ApiException.class);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM question_embeddings WHERE question_id=?",
                                Integer.class,
                                q))
                .isZero();
    }

    @Test
    void badModelVectorsReadinessAndCallerTransactionsFailWithoutReadyRows() throws Exception {
        badVector = true;
        assertThatThrownBy(() -> actor(owner, () -> indexing.index(source)))
                .isInstanceOf(AiClientException.class);
        badVector = false;
        off = true;
        assertThatThrownBy(() -> actor(owner, () -> indexing.index(source)))
                .isInstanceOf(AiClientException.class);
        off = false;
        int count = requests.size();
        assertThatThrownBy(
                        () ->
                                actor(
                                        owner,
                                        () ->
                                                new TransactionTemplate(tx)
                                                        .execute(status -> indexing.index(source))))
                .isInstanceOf(IllegalStateException.class);
        assertThat(requests).hasSize(count);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM question_embeddings WHERE question_id=?",
                                Integer.class,
                                source))
                .isZero();
    }

    @Test
    void everySourceEvidenceVersionMustCoverTheCandidateDate() throws Exception {
        var extra =
                imports.importVersion(
                        new LawImport(
                                "LAW_GO_KR",
                                "SYNTHETIC-SECOND-" + UUID.randomUUID(),
                                "v1",
                                "SYNTHETIC SECOND",
                                LocalDate.of(2025, 1, 1),
                                LocalDate.of(2027, 1, 1),
                                "VERIFIED",
                                "https://www.law.go.kr/TEST-ONLY",
                                Map.of("synthetic", true),
                                List.of(
                                        new LawImport.Article(
                                                "a1",
                                                "TEST",
                                                "합성 원문",
                                                List.of(new LawImport.Span(0, 5, "합성 원문"))))));
        jdbc.update(
                "INSERT INTO evidence_results(id,run_id,chunk_id,ordinal,quote,start_offset,end_offset,metadata_snapshot) VALUES (?,?,?,1,'합성 원문',0,5,'{}'::jsonb)",
                UUID.randomUUID(),
                run,
                extra.chunkIds().getFirst());
        var old = question(other, "2024-12-31");
        var valid = question(other, "2025-01-01");
        reviewedPublic(old);
        reviewedPublic(valid);
        vector(source, owner, 1);
        vector(old, other, 1);
        vector(valid, other, 1);
        assertThat(list())
                .extracting(RecommendationService.Item::questionId)
                .containsExactly(valid);
    }

    @Test
    void concurrentIndexPublicationConvergesOnOneReadyRow() throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        try {
            var start = new CountDownLatch(1);
            var futures = new ArrayList<Future<QuestionEmbeddingService.Outcome>>();
            for (int i = 0; i < 2; i++)
                futures.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    return actor(owner, () -> indexing.index(source));
                                }));
            start.countDown();
            var results = new ArrayList<QuestionEmbeddingService.Outcome>();
            for (var f : futures) results.add(f.get(30, TimeUnit.SECONDS));
            assertThat(results)
                    .containsExactlyInAnyOrder(
                            QuestionEmbeddingService.Outcome.INDEXED,
                            QuestionEmbeddingService.Outcome.REUSED);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM question_embeddings WHERE question_id=?",
                                    Integer.class,
                                    source))
                    .isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }
}
