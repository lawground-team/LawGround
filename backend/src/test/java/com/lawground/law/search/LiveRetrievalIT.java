package com.lawground.law.search;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lawground.global.config.JsonData;
import com.lawground.integration.ai.client.AiClientException;
import com.lawground.integration.ai.dto.AiDtos.*;
import com.lawground.law.dto.LawImport;
import com.lawground.law.service.LawImportService;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;

/** Real PG/HTTP integration with synthetic model responses; not a real-weight quality test. */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class LiveRetrievalIT {
    static final String MODEL = "a".repeat(40),
            RERANK = "b".repeat(40),
            TOKENIZER = "kiwipiepy-0.24.0+rules-1";
    static final String TOKEN = "synthetic-live-http-test-secret";
    static final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    static final HttpServer server = server();
    static final AtomicInteger posts = new AtomicInteger();
    static final List<String> paths = new CopyOnWriteArrayList<>(),
            violations = new CopyOnWriteArrayList<>();
    static volatile String failurePath;
    static volatile Runnable onEmbed = () -> {}, onRerank = () -> {};
    static volatile boolean foreignCandidate, emptyTokens;

    @Container @ServiceConnection
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(
                    DockerImageName.parse("pgvector/pgvector:0.8.2-pg16")
                            .asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void settings(DynamicPropertyRegistry settings) {
        settings.add(
                "lawground.ai.base-url",
                () -> "http://127.0.0.1:" + server.getAddress().getPort() + "/internal/v1");
        settings.add("lawground.ai.api-key", () -> TOKEN);
        settings.add("lawground.ai.embedding-model-revision", () -> MODEL);
        settings.add("lawground.ai.tokenizer-version", () -> TOKENIZER);
        settings.add("lawground.ai.reranker-model-revision", () -> RERANK);
    }

    @Autowired LiveLawIndexService indexes;
    @Autowired LiveRetrievalService retrieval;
    @Autowired LiveIndexStore store;
    @Autowired LawImportService imports;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;

    @BeforeEach
    void reset() {
        posts.set(0);
        paths.clear();
        violations.clear();
        failurePath = null;
        onEmbed = () -> {};
        onRerank = () -> {};
        foreignCandidate = false;
        emptyTokens = false;
    }

    @AfterEach
    void contract() {
        assertThat(violations).isEmpty();
    }

    @AfterAll
    static void stop() {
        server.stop(0);
    }

    @Test
    void indexesAtomicallyReusesAndRetrievesRerankedOriginals() {
        var imported = corpus("LAW_GO_KR", "VERIFIED", "alpha 원문😀", "beta 원문");
        UUID law = lawId(imported);
        onEmbed = this::assertNoOpenSourceTransaction;
        for (UUID chunk : imported.chunkIds())
            assertThat(indexes.indexChunk(chunk)).isEqualTo(LiveLawIndexService.Outcome.INDEXED);
        assertThat(posts.get()).isEqualTo(4);
        for (UUID chunk : imported.chunkIds())
            assertThat(indexes.indexChunk(chunk)).isEqualTo(LiveLawIndexService.Outcome.REUSED);
        assertThat(posts.get()).isEqualTo(4);
        var result = retrieval.search(query(Set.of(law), 2));
        assertThat(result.status()).isEqualTo(LiveRetrievalService.Status.RETRIEVED);
        assertThat(result.verificationPerformed()).isFalse();
        assertThat(result.hits()).hasSize(2);
        assertThat(result.manifestHash()).hasSize(64);
        assertThat(result.rerankerRevision()).isEqualTo(RERANK);
        // Stub reverses RRF order: proves rerank output, not cosine/RRF score sorting, is used.
        assertThat(result.hits().getFirst().sourceText()).isEqualTo("beta 원문");
        assertThat(result.hits().getFirst().rerankScore())
                .isGreaterThan(result.hits().get(1).rerankScore());
        assertThat(result.lawVersionIds()).containsExactly(imported.versionId());
        assertThat(paths)
                .containsExactly(
                        "tokenize", "embed", "tokenize", "embed", "tokenize", "embed", "rerank");
    }

    @Test
    void failureDoesNotExposePartialReadyAndNextInvocationResumes() {
        var imported = corpus("LAW_GO_KR", "VERIFIED", "alpha 합성 원문");
        UUID chunk = imported.chunkIds().getFirst();
        failurePath = "embed";
        assertThatThrownBy(() -> indexes.indexChunk(chunk)).isInstanceOf(AiClientException.class);
        assertThat(countIndexes(chunk)).isZero();
        assertThat(retrieval.search(query(Set.of(lawId(imported)), 1)).status())
                .isEqualTo(LiveRetrievalService.Status.INDEX_NOT_READY);
        int attempted = posts.get();
        failurePath = null;
        assertThat(indexes.indexChunk(chunk)).isEqualTo(LiveLawIndexService.Outcome.INDEXED);
        assertThat(posts.get()).isEqualTo(attempted + 2);
        assertThat(countIndexes(chunk)).isEqualTo(1);
    }

    @Test
    void missingDateCorpusAndPartiallyIndexedCorpusNeverCallModels() {
        var imported = corpus("LAW_GO_KR", "VERIFIED", "alpha", "beta");
        indexes.indexChunk(imported.chunkIds().getFirst());
        posts.set(0);
        assertThat(retrieval.search(query(Set.of(lawId(imported)), 1)).status())
                .isEqualTo(LiveRetrievalService.Status.INDEX_NOT_READY);
        assertThat(
                        retrieval
                                .search(
                                        new LiveRetrievalService.Query(
                                                Set.of(lawId(imported)),
                                                LocalDate.of(2020, 1, 1),
                                                "alpha",
                                                LawImportService.CHUNKER,
                                                1,
                                                1))
                                .status())
                .isEqualTo(LiveRetrievalService.Status.LAW_VERSION_UNAVAILABLE);
        assertThat(retrieval.search(query(Set.of(lawId(imported), UUID.randomUUID()), 1)).status())
                .isEqualTo(LiveRetrievalService.Status.LAW_VERSION_UNAVAILABLE);
        assertThat(posts.get()).isZero();
    }

    @Test
    void refusesFixtureAndUnreviewedSourcesEvenInTestProfile() {
        for (var imported :
                List.of(
                        corpus("FIXTURE", "VERIFIED", "alpha"),
                        corpus("LAW_GO_KR", "REVIEW_REQUIRED", "alpha"))) {
            assertThatThrownBy(() -> indexes.indexChunk(imported.chunkIds().getFirst()))
                    .isInstanceOf(LiveSearchFailure.class);
            assertThat(retrieval.search(query(Set.of(lawId(imported)), 1)).status())
                    .isEqualTo(LiveRetrievalService.Status.LAW_VERSION_UNAVAILABLE);
        }
        assertThat(posts.get()).isZero();
    }

    @Test
    void changingSourceDuringInferencePreventsAnyWrite() {
        var imported = corpus("LAW_GO_KR", "VERIFIED", "alpha");
        UUID chunk = imported.chunkIds().getFirst();
        onEmbed =
                () ->
                        jdbc.update(
                                "UPDATE law_chunks SET search_text='changed',content_hash=? WHERE id=?",
                                JsonData.hash("changed"),
                                chunk);
        assertThatThrownBy(() -> indexes.indexChunk(chunk))
                .isInstanceOf(LiveSearchFailure.class)
                .hasMessage("SOURCE_CHANGED");
        assertThat(countIndexes(chunk)).isZero();
    }

    @Test
    void rejectsCorruptOriginalHashAndIncompleteChunkCoverage() {
        var imported = corpus("LAW_GO_KR", "VERIFIED", "alpha");
        UUID chunk = imported.chunkIds().getFirst();
        jdbc.update("UPDATE law_chunks SET content_hash=? WHERE id=?", "0".repeat(64), chunk);
        assertThatThrownBy(() -> indexes.indexChunk(chunk)).hasMessage("INVALID_SOURCE");
        assertThat(posts.get()).isZero();
        jdbc.update(
                "UPDATE law_chunks SET content_hash=?,article_start=1,source_text='lpha' WHERE id=?",
                JsonData.hash("alpha"),
                chunk);
        assertThatThrownBy(() -> retrieval.search(query(Set.of(lawId(imported)), 1)))
                .hasMessage("INVALID_SOURCE");
        assertThat(posts.get()).isZero();
    }

    @Test
    void unfinishedIndexRowCanBeRebuiltButCompleteCorruptRowFailsClosed() {
        var imported = corpus("LAW_GO_KR", "VERIFIED", "alpha");
        UUID chunk = imported.chunkIds().getFirst();
        indexes.indexChunk(chunk);
        jdbc.update(
                "UPDATE law_chunk_indexes SET dense_status='FAILED',embedding=NULL,error_code='SIMULATED_FAILURE' WHERE chunk_id=?",
                chunk);
        assertThat(indexes.indexChunk(chunk)).isEqualTo(LiveLawIndexService.Outcome.INDEXED);
        jdbc.update(
                "UPDATE law_chunk_indexes SET search_vector=to_tsvector('simple','unrelated') WHERE chunk_id=?",
                chunk);
        assertThatThrownBy(() -> indexes.indexChunk(chunk)).hasMessage("INVALID_INDEX");
        assertThat(retrieval.search(query(Set.of(lawId(imported)), 1)).status())
                .isEqualTo(LiveRetrievalService.Status.INDEX_NOT_READY);
    }

    @Test
    void concurrentPublishKeepsOneCompleteRow() throws Exception {
        var imported = corpus("LAW_GO_KR", "VERIFIED", "alpha");
        UUID chunk = imported.chunkIds().getFirst();
        var entered = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        onEmbed =
                () -> {
                    entered.countDown();
                    await(release);
                };
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> indexes.indexChunk(chunk));
            var second = pool.submit(() -> indexes.indexChunk(chunk));
            assertThat(entered.await(15, TimeUnit.SECONDS)).isTrue();
            release.countDown();
            assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(
                            LiveLawIndexService.Outcome.INDEXED,
                            LiveLawIndexService.Outcome.REUSED);
            assertThat(countIndexes(chunk)).isEqualTo(1);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void modelCallsCannotRunInsideCallerTransaction() {
        var imported = corpus("LAW_GO_KR", "VERIFIED", "alpha");
        var tx = new TransactionTemplate(transactionManager);
        assertThatThrownBy(
                        () ->
                                tx.executeWithoutResult(
                                        status ->
                                                indexes.indexChunk(imported.chunkIds().getFirst())))
                .hasMessage("TRANSACTION_FORBIDDEN");
        assertThatThrownBy(
                        () ->
                                tx.executeWithoutResult(
                                        status ->
                                                retrieval.search(
                                                        query(Set.of(lawId(imported)), 1))))
                .hasMessage("TRANSACTION_FORBIDDEN");
        assertThat(posts.get()).isZero();
    }

    @Test
    void rerankFailureAndForeignIdAreNotEmptyOrLegalHold() {
        var imported = corpus("LAW_GO_KR", "VERIFIED", "alpha");
        indexes.indexChunk(imported.chunkIds().getFirst());
        var query = query(Set.of(lawId(imported)), 1);
        failurePath = "rerank";
        assertThatThrownBy(() -> retrieval.search(query)).isInstanceOf(AiClientException.class);
        failurePath = null;
        foreignCandidate = true;
        assertThatThrownBy(() -> retrieval.search(query)).isInstanceOf(AiClientException.class);
    }

    @Test
    void changingCorpusWhileRerankingRejectsStaleResults() {
        var imported = corpus("LAW_GO_KR", "VERIFIED", "alpha");
        indexes.indexChunk(imported.chunkIds().getFirst());
        onRerank =
                () ->
                        jdbc.update(
                                "UPDATE law_versions SET applicability_status='REVIEW_REQUIRED' WHERE id=?",
                                imported.versionId());
        assertThatThrownBy(() -> retrieval.search(query(Set.of(lawId(imported)), 1)))
                .hasMessage("CORPUS_CHANGED");
    }

    @Test
    void candidateBudgetIsExplicitAndRecorded() {
        var imported = corpus("LAW_GO_KR", "VERIFIED", "alpha", "beta");
        imported.chunkIds().forEach(indexes::indexChunk);
        var result = retrieval.search(query(Set.of(lawId(imported)), 1));
        assertThat(result.fusedCandidateCount()).isEqualTo(2);
        assertThat(result.rerankedCandidateCount()).isEqualTo(1);
        assertThat(result.hits()).hasSize(1);
        assertThatThrownBy(
                        () ->
                                new LiveRetrievalService.Query(
                                        Set.of(lawId(imported)),
                                        LocalDate.now(),
                                        "alpha",
                                        LawImportService.CHUNKER,
                                        31,
                                        1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void embeddingPinMismatchLeavesExistingRowsUnused() {
        var imported = corpus("LAW_GO_KR", "VERIFIED", "alpha");
        indexes.indexChunk(imported.chunkIds().getFirst());
        jdbc.update(
                "UPDATE law_chunk_indexes SET embedding_model_revision=? WHERE chunk_id=?",
                "c".repeat(40),
                imported.chunkIds().getFirst());
        posts.set(0);
        assertThat(retrieval.search(query(Set.of(lawId(imported)), 1)).status())
                .isEqualTo(LiveRetrievalService.Status.INDEX_NOT_READY);
        assertThat(posts.get()).isZero();
    }

    @Test
    void cachedReadyRowCannotBeReusedAfterSourceSnapshotChanges() {
        var imported = corpus("LAW_GO_KR", "VERIFIED", "alpha");
        UUID chunk = imported.chunkIds().getFirst();
        indexes.indexChunk(chunk);
        var profile = new LiveSearchProfile(MODEL, TOKENIZER, LawImportService.CHUNKER);
        var snapshot = store.load(chunk, profile);
        jdbc.update(
                "UPDATE law_versions SET applicability_status='REVIEW_REQUIRED' WHERE id=?",
                imported.versionId());
        assertThatThrownBy(() -> store.ready(snapshot, profile)).hasMessage("SOURCE_CHANGED");
    }

    @Test
    void manifestRejectsIndexRebuildDuringReranking() {
        var imported = corpus("LAW_GO_KR", "VERIFIED", "alpha");
        UUID chunk = imported.chunkIds().getFirst();
        indexes.indexChunk(chunk);
        onRerank =
                () ->
                        jdbc.update(
                                "UPDATE law_chunk_indexes SET updated_at=updated_at+interval '1 second' WHERE chunk_id=?",
                                chunk);
        assertThatThrownBy(() -> retrieval.search(query(Set.of(lawId(imported)), 1)))
                .hasMessage("CORPUS_CHANGED");
    }

    @Test
    void emptyLexicalTokensStillAllowTheDenseChannel() {
        emptyTokens = true;
        var imported = corpus("LAW_GO_KR", "VERIFIED", "alpha");
        indexes.indexChunk(imported.chunkIds().getFirst());
        var result = retrieval.search(query(Set.of(lawId(imported)), 1));
        assertThat(result.status()).isEqualTo(LiveRetrievalService.Status.RETRIEVED);
        assertThat(result.hits()).hasSize(1);
    }

    private LiveRetrievalService.Query query(Set<UUID> laws, int limit) {
        return new LiveRetrievalService.Query(
                laws, LocalDate.of(2025, 1, 1), "alpha", LawImportService.CHUNKER, limit, limit);
    }

    private UUID lawId(LawImportService.Imported imported) {
        return jdbc.queryForObject(
                "SELECT law_id FROM law_versions WHERE id=?", UUID.class, imported.versionId());
    }

    private int countIndexes(UUID chunk) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM law_chunk_indexes WHERE chunk_id=?", Integer.class, chunk);
    }

    private void assertNoOpenSourceTransaction() {
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND state='idle in transaction' AND query ILIKE '%law_chunks%'",
                                Integer.class))
                .isZero();
    }

    private LawImportService.Imported corpus(String provider, String status, String... texts) {
        var articles = new ArrayList<LawImport.Article>();
        for (int i = 0; i < texts.length; i++)
            articles.add(
                    new LawImport.Article(
                            "a" + i,
                            "SYNTHETIC-TEST-ARTICLE-" + i,
                            texts[i],
                            List.of(
                                    new LawImport.Span(
                                            0,
                                            texts[i].codePointCount(0, texts[i].length()),
                                            texts[i]))));
        // This synthetic source exists only in this disposable Testcontainers DB.
        return imports.importVersion(
                new LawImport(
                        provider,
                        "SYNTHETIC-" + UUID.randomUUID(),
                        "test-version",
                        "SYNTHETIC TEST CORPUS",
                        LocalDate.of(2024, 1, 1),
                        null,
                        status,
                        provider.equals("FIXTURE")
                                ? "https://example.invalid/test"
                                : "https://www.law.go.kr/TEST-ONLY",
                        Map.of("synthetic", true),
                        articles));
    }

    private static void await(CountDownLatch gate) {
        try {
            if (!gate.await(15, TimeUnit.SECONDS))
                throw new IllegalStateException("Test gate timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Test interrupted");
        }
    }

    private static HttpServer server() {
        try {
            HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            http.setExecutor(
                    Executors.newCachedThreadPool(
                            r -> {
                                var thread = new Thread(r);
                                thread.setDaemon(true);
                                return thread;
                            }));
            http.createContext(
                    "/internal/v1/",
                    exchange -> {
                        String path =
                                exchange.getRequestURI()
                                        .getPath()
                                        .substring("/internal/v1/".length());
                        Object response;
                        int status = 200;
                        if (!TOKEN.equals(
                                exchange.getRequestHeaders().getFirst("X-Internal-Token")))
                            violations.add("internal-token");
                        if (path.equals("health/ready")) {
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
                        } else {
                            posts.incrementAndGet();
                            paths.add(path);
                            JsonNode request = json.readTree(exchange.getRequestBody());
                            if (!request.path("requestId")
                                    .asText()
                                    .equals(exchange.getRequestHeaders().getFirst("X-Request-Id")))
                                violations.add("request-id");
                            if (!"0.1.0-draft"
                                    .equals(
                                            exchange.getRequestHeaders()
                                                    .getFirst("X-Contract-Version")))
                                violations.add("contract-version");
                            if (path.equals(failurePath)) {
                                status = 503;
                                response =
                                        Map.of(
                                                "code",
                                                "MODEL_NOT_READY",
                                                "message",
                                                "synthetic failure",
                                                "requestId",
                                                request.path("requestId").asText(),
                                                "timestamp",
                                                "2026-10-22T00:00:00Z",
                                                "retryable",
                                                false);
                            } else if (path.equals("tokenize")) {
                                var items = new ArrayList<TokenItem>();
                                request.path("texts")
                                        .forEach(
                                                text ->
                                                        items.add(
                                                                new TokenItem(
                                                                        UUID.fromString(
                                                                                text.path("id")
                                                                                        .asText()),
                                                                        emptyTokens
                                                                                ? List.of()
                                                                                : List.of(
                                                                                        text.path(
                                                                                                                "text")
                                                                                                        .asText()
                                                                                                        .contains(
                                                                                                                "beta")
                                                                                                ? "beta"
                                                                                                : "alpha"))));
                                response = new TokenizeResponse(items, TOKENIZER);
                            } else if (path.equals("embed")) {
                                onEmbed.run();
                                var items = new ArrayList<EmbedItem>();
                                request.path("texts")
                                        .forEach(
                                                text -> {
                                                    var vector =
                                                            new ArrayList<>(
                                                                    Collections.nCopies(1024, 0.0));
                                                    vector.set(
                                                            text.path("text")
                                                                            .asText()
                                                                            .contains("beta")
                                                                    ? 1
                                                                    : 0,
                                                            1.0);
                                                    items.add(
                                                            new EmbedItem(
                                                                    UUID.fromString(
                                                                            text.path("id")
                                                                                    .asText()),
                                                                    vector));
                                                });
                                response = new EmbedResponse(items, 1024, MODEL);
                            } else if (path.equals("rerank")) {
                                onRerank.run();
                                var items = new ArrayList<RerankItem>();
                                JsonNode candidates = request.path("candidates");
                                for (int i = candidates.size() - 1;
                                        i >= 0 && items.size() < request.path("topK").asInt();
                                        i--)
                                    items.add(
                                            new RerankItem(
                                                    foreignCandidate
                                                            ? UUID.randomUUID()
                                                            : UUID.fromString(
                                                                    candidates
                                                                            .get(i)
                                                                            .path("chunkId")
                                                                            .asText()),
                                                    10.0 - items.size()));
                                response = new RerankResponse(items, RERANK);
                            } else {
                                status = 404;
                                response = Map.of();
                            }
                        }
                        byte[] body = json.writeValueAsBytes(response);
                        exchange.getResponseHeaders().set("Content-Type", "application/json");
                        exchange.sendResponseHeaders(status, body.length);
                        try (var output = exchange.getResponseBody()) {
                            output.write(body);
                        }
                    });
            http.start();
            return http;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
