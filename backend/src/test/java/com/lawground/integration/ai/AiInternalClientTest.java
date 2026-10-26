package com.lawground.integration.ai;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lawground.integration.ai.client.AiClientException;
import com.lawground.integration.ai.client.AiInternalClient;
import com.lawground.integration.ai.config.AiProperties;
import com.lawground.integration.ai.dto.AiDtos.*;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Real HTTP boundary tests with synthetic responses, not actual BGE model execution. */
class AiInternalClientTest {
    private static final String EMBED = "a".repeat(40);
    private static final String RERANK = "b".repeat(40);
    private static final String TOKENIZER = "kiwipiepy-0.24.0+rules-1";
    private static final String TOKEN = "synthetic-internal-test-token";
    private static final UUID ID1 = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID ID2 = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final AtomicInteger posts = new AtomicInteger();
    private final AtomicInteger gets = new AtomicInteger();
    private HttpServer server;
    private URI base;
    private AiInternalClient client;
    private volatile int readyStatus;
    private volatile String readyBody;
    private volatile int responseStatus;
    private volatile String responseBody;
    private volatile String responseContentType;
    private volatile long delayMillis;
    private volatile String observedPath;
    private volatile String observedToken;
    private volatile String observedRequestId;
    private volatile String observedContract;
    private volatile String observedBody;

    @BeforeEach
    void start() throws Exception {
        readyStatus = 200;
        readyBody = json(readyOutput(true, true));
        responseStatus = 200;
        responseContentType = "application/json";
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(
                Executors.newCachedThreadPool(
                        r -> {
                            Thread thread = new Thread(r);
                            thread.setDaemon(true);
                            return thread;
                        }));
        server.createContext("/", this::respond);
        server.start();
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/internal/v1");
        client = new AiInternalClient(properties(Duration.ofSeconds(3), 4 * 1024 * 1024), mapper);
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void tokenizationUsesMatchingHeadersBodyIdsVersionAndInternalPrefix() throws Exception {
        responseBody =
                json(
                        new TokenizeResponse(
                                List.of(new TokenItem(ID1, List.of("중개", "보수"))), TOKENIZER));
        UUID requestId = UUID.randomUUID();
        var out = client.tokenize(requestId, texts());
        assertThat(out.items().getFirst().tokens()).containsExactly("중개", "보수");
        assertThat(observedPath).isEqualTo("/internal/v1/tokenize");
        assertThat(observedToken).isEqualTo(TOKEN);
        assertThat(observedRequestId).isEqualTo(requestId.toString());
        assertThat(observedContract).isEqualTo(AiInternalClient.CONTRACT_VERSION);
        var body = mapper.readTree(observedBody);
        assertThat(body.get("requestId").asText()).isEqualTo(requestId.toString());
        assertThat(body.get("tokenizerVersion").asText()).isEqualTo(TOKENIZER);
        assertThat(body.get("texts").get(0).get("id").asText()).isEqualTo(ID1.toString());
        assertThat(posts.get()).isEqualTo(1);
    }

    @Test
    void embedValidatesRealCapabilityAndAcceptsFiniteNonzero1024Vector() {
        responseBody = json(new EmbedResponse(List.of(new EmbedItem(ID1, vector())), 1024, EMBED));
        var out = client.embed(UUID.randomUUID(), texts());
        assertThat(out.dim()).isEqualTo(1024);
        assertThat(out.items().getFirst().vector()).hasSize(1024);
        assertThat(observedPath).isEqualTo("/internal/v1/embed");
    }

    @Test
    void rerankScoresAreRawLogitsAndTiesPreserveInputOrder() {
        responseBody =
                json(
                        new RerankResponse(
                                List.of(new RerankItem(ID1, 3.5), new RerankItem(ID2, 3.5)),
                                RERANK));
        var out = client.rerank(UUID.randomUUID(), "검수 질의", candidates(), 5);
        assertThat(out.ranked()).extracting(RerankItem::chunkId).containsExactly(ID1, ID2);
        assertThat(out.ranked().getFirst().score()).isGreaterThan(1);
    }

    @Test
    void global503DoesNotBlockActuallyReadyPinnedTokenization() {
        readyStatus = 503;
        readyBody = json(readyOutput(false, false));
        responseBody =
                json(new TokenizeResponse(List.of(new TokenItem(ID1, List.of())), TOKENIZER));
        assertThat(client.ready().status()).isEqualTo(ReadyStatus.NOT_READY);
        assertThat(client.tokenize(UUID.randomUUID(), texts()).items()).hasSize(1);
        assertThatThrownBy(() -> client.embed(UUID.randomUUID(), texts()))
                .isInstanceOfSatisfying(
                        AiClientException.class,
                        failure ->
                                assertThat(failure.kind())
                                        .isEqualTo(AiClientException.Kind.NOT_READY));
        assertThat(posts.get()).isEqualTo(1);
    }

    @Test
    void mockReadyCannotMasqueradeAsLiveAndDoesNotReachPost() {
        readyBody =
                json(
                        new ReadyOutput(
                                ReadyStatus.READY,
                                List.of(
                                        new ModelState("tokenizer:kiwipiepy", TOKENIZER, true),
                                        new ModelState("embed:mock", EMBED, true))));
        assertThatThrownBy(() -> client.embed(UUID.randomUUID(), texts()))
                .isInstanceOfSatisfying(
                        AiClientException.class,
                        failure ->
                                assertThat(failure.kind())
                                        .isEqualTo(AiClientException.Kind.NOT_READY));
        assertThat(posts.get()).isZero();
    }

    @Test
    void changedRevisionIsNotAutomaticallyAdopted() {
        readyBody =
                json(
                        new ReadyOutput(
                                ReadyStatus.READY,
                                List.of(
                                        new ModelState(
                                                "embed:BAAI/bge-m3", "c".repeat(40), true))));
        assertThatThrownBy(() -> client.embed(UUID.randomUUID(), texts()))
                .isInstanceOfSatisfying(
                        AiClientException.class,
                        failure ->
                                assertThat(failure.kind())
                                        .isEqualTo(AiClientException.Kind.VERSION_MISMATCH));
        assertThat(posts.get()).isZero();
    }

    @Test
    void inputBoundsRejectBeforeAnyNetworkCallAndUseCodePoints() {
        List<List<IdText>> invalid = new ArrayList<>();
        invalid.add(List.of());
        invalid.add(Collections.nCopies(33, new IdText(ID1, "문장")));
        invalid.add(List.of(new IdText(ID1, "문장"), new IdText(ID1, "문장")));
        invalid.add(List.of(new IdText(ID1, " ")));
        invalid.add(List.of(new IdText(ID1, "😀".repeat(30001))));
        invalid.add(List.of(new IdText(null, "문장")));
        for (var value : invalid) assertInput(() -> client.tokenize(UUID.randomUUID(), value));
        assertInput(() -> client.embed(null, texts()));
        assertInput(() -> client.rerank(UUID.randomUUID(), "질의", candidates(), 6));
        assertInput(() -> client.rerank(UUID.randomUUID(), " ", candidates(), 1));
        assertInput(
                () ->
                        client.rerank(
                                UUID.randomUUID(),
                                "질의",
                                List.of(
                                        new RerankCandidate(ID1, "문장"),
                                        new RerankCandidate(ID1, "문장")),
                                1));
        assertThat(gets.get()).isZero();
        assertThat(posts.get()).isZero();
        responseBody =
                json(new TokenizeResponse(List.of(new TokenItem(ID1, List.of())), TOKENIZER));
        assertThat(
                        client.tokenize(
                                        UUID.randomUUID(),
                                        List.of(new IdText(ID1, "😀".repeat(30000))))
                                .items())
                .hasSize(1);
    }

    @Test
    void malformedUnknownDuplicateTrailingAndWrongJsonTypesAreRejected() {
        String valid =
                json(new TokenizeResponse(List.of(new TokenItem(ID1, List.of("법"))), TOKENIZER));
        for (String invalid :
                List.of(
                        "not-json",
                        valid + " {}",
                        valid.replace("\"tokens\":[\"법\"]", "\"tokens\":[1]"),
                        valid.replace("\"tokens\":[\"법\"]", "\"tokens\":null"),
                        valid.replace("\"tokens\":[\"법\"]", "\"tokens\":[null]"),
                        valid.replace(
                                "\"tokenizerVersion\":", "\"extra\":true,\"tokenizerVersion\":"),
                        valid.replace(
                                "\"tokenizerVersion\":",
                                "\"tokenizerVersion\":\"duplicate\",\"tokenizerVersion\":"),
                        "null",
                        "{}")) {
            responseBody = invalid;
            assertProtocol(() -> client.tokenize(UUID.randomUUID(), texts()));
        }
    }

    @Test
    void tokenIdsOrderLengthsAndVersionMustMatch() {
        for (TokenizeResponse invalid :
                List.of(
                        new TokenizeResponse(List.of(new TokenItem(ID2, List.of())), TOKENIZER),
                        new TokenizeResponse(List.of(), TOKENIZER),
                        new TokenizeResponse(
                                List.of(new TokenItem(ID1, List.of("法".repeat(201)))), TOKENIZER),
                        new TokenizeResponse(
                                List.of(new TokenItem(ID1, Collections.nCopies(10001, "법"))),
                                TOKENIZER),
                        new TokenizeResponse(List.of(new TokenItem(ID1, List.of())), "other"))) {
            responseBody = json(invalid);
            assertProtocol(() -> client.tokenize(UUID.randomUUID(), texts()));
        }
        responseBody =
                json(
                        new TokenizeResponse(
                                List.of(
                                        new TokenItem(ID2, List.of()),
                                        new TokenItem(ID1, List.of())),
                                TOKENIZER));
        assertProtocol(
                () ->
                        client.tokenize(
                                UUID.randomUUID(),
                                List.of(new IdText(ID1, "일"), new IdText(ID2, "이"))));
    }

    @Test
    void invalidDimensionsZeroNonfiniteNullAndStringVectorValuesAreRejected() {
        String valid = json(new EmbedResponse(List.of(new EmbedItem(ID1, vector())), 1024, EMBED));
        List<Double> nullVector = vector();
        nullVector.set(0, null);
        for (String invalid :
                List.of(
                        json(
                                new EmbedResponse(
                                        List.of(new EmbedItem(ID1, Collections.nCopies(1024, 0.0))),
                                        1024,
                                        EMBED)),
                        json(
                                new EmbedResponse(
                                        List.of(new EmbedItem(ID1, List.of(1.0))), 1024, EMBED)),
                        json(new EmbedResponse(List.of(new EmbedItem(ID2, vector())), 1024, EMBED)),
                        json(
                                new EmbedResponse(
                                        List.of(new EmbedItem(ID1, nullVector)), 1024, EMBED)),
                        valid.replace("\"dim\":1024", "\"dim\":1024.0"),
                        valid.replace("\"dim\":1024", "\"dim\":null"),
                        valid.replace("[1.0,", "[\"1.0\","),
                        valid.replace("[1.0,", "[1e1000,"),
                        valid.replace(EMBED, RERANK))) {
            responseBody = invalid;
            assertProtocol(() -> client.embed(UUID.randomUUID(), texts()));
        }
    }

    @Test
    void rerankRejectsUnknownDuplicatesUnsortedTiesAndTooManyResults() {
        for (RerankResponse invalid :
                List.of(
                        new RerankResponse(List.of(new RerankItem(UUID.randomUUID(), 1)), RERANK),
                        new RerankResponse(
                                List.of(new RerankItem(ID1, 1), new RerankItem(ID1, 0)), RERANK),
                        new RerankResponse(
                                List.of(new RerankItem(ID1, 0), new RerankItem(ID2, 1)), RERANK),
                        new RerankResponse(
                                List.of(new RerankItem(ID2, 1), new RerankItem(ID1, 1)), RERANK),
                        new RerankResponse(List.of(), RERANK),
                        new RerankResponse(List.of(new RerankItem(ID1, 1)), EMBED))) {
            responseBody = json(invalid);
            assertProtocol(() -> client.rerank(UUID.randomUUID(), "질의", candidates(), 2));
        }
        responseBody =
                json(
                        new RerankResponse(
                                List.of(new RerankItem(ID1, 1), new RerankItem(ID2, 0)), RERANK));
        assertProtocol(() -> client.rerank(UUID.randomUUID(), "질의", candidates(), 1));
    }

    @Test
    void readinessRequiresStrictPrimitiveTypesStatusAndUniqueCapabilityNames() {
        String valid = json(readyOutput(true, true));
        for (String invalid :
                List.of(
                        valid.replace("\"ready\":true", "\"ready\":null"),
                        valid.replace("\"ready\":true", "\"ready\":\"true\""),
                        valid.replace("\"status\":\"READY\"", "\"status\":\"NOT_READY\""),
                        json(
                                new ReadyOutput(
                                        ReadyStatus.READY,
                                        List.of(
                                                new ModelState("embed:BAAI/bge-m3", EMBED, true),
                                                new ModelState("embed:mock", EMBED, true)))))) {
            readyBody = invalid;
            assertProtocol(client::ready);
        }
    }

    @Test
    void remoteErrorsExposeOnlySafeCodeStatusAndDoNotRetry() {
        for (var entry :
                Map.of(
                                401,
                                "UNAUTHORIZED",
                                409,
                                "MODEL_REVISION_MISMATCH",
                                422,
                                "INPUT_TOO_LONG",
                                429,
                                "RATE_LIMITED",
                                500,
                                "INTERNAL_ERROR",
                                503,
                                "MODEL_NOT_READY",
                                504,
                                "MODEL_TIMEOUT")
                        .entrySet()) {
            responseStatus = entry.getKey();
            responseBody = "DYNAMIC_ERROR:" + entry.getValue();
            int previous = posts.get();
            assertThatThrownBy(() -> client.tokenize(UUID.randomUUID(), texts()))
                    .isInstanceOfSatisfying(
                            AiClientException.class,
                            failure -> {
                                assertThat(failure.kind())
                                        .isEqualTo(AiClientException.Kind.REMOTE_ERROR);
                                assertThat(failure.code()).isEqualTo(entry.getValue());
                                assertThat(failure.httpStatus()).isEqualTo(entry.getKey());
                                assertThat(failure.retryable())
                                        .isEqualTo(entry.getKey() == 429 || entry.getKey() == 504);
                                assertThat(failure.getMessage()).doesNotContain("sensitive", TOKEN);
                                assertThat(failure.getCause()).isNull();
                            });
            assertThat(posts.get()).isEqualTo(previous + 1);
        }
    }

    @Test
    void invalidErrorIdOrStatusCodeCombinationIsProtocolFailure() {
        responseStatus = 401;
        responseBody =
                json(
                        Map.of(
                                "code",
                                "UNAUTHORIZED",
                                "message",
                                "sensitive remote input",
                                "requestId",
                                ID1,
                                "timestamp",
                                "2026-10-14T10:00:00Z",
                                "retryable",
                                false));
        assertProtocol(() -> client.tokenize(UUID.randomUUID(), texts()));
        responseBody = "DYNAMIC_ERROR:INTERNAL_ERROR";
        assertProtocol(() -> client.tokenize(UUID.randomUUID(), texts()));
        responseBody = "DYNAMIC_ERROR:NULL_CODE";
        assertProtocol(() -> client.tokenize(UUID.randomUUID(), texts()));
    }

    @Test
    void oversizedBodyAndWrongContentTypeAreRejected() {
        client = new AiInternalClient(properties(Duration.ofSeconds(3), 1024), mapper);
        responseBody = "x".repeat(2048);
        assertProtocol(() -> client.tokenize(UUID.randomUUID(), texts()));
        responseBody = "{}";
        responseContentType = "text/html";
        assertProtocol(() -> client.tokenize(UUID.randomUUID(), texts()));
    }

    @Test
    void redirectsAreNotFollowed() {
        responseStatus = 302;
        responseBody = "{}";
        assertProtocol(() -> client.tokenize(UUID.randomUUID(), texts()));
        assertThat(posts.get()).isEqualTo(1);
    }

    @Test
    void deadlineAndConnectionFailureDoNotAutomaticallyRetry() {
        client.ready(); // Warm the transport before the intentional short deadline.
        client = new AiInternalClient(properties(Duration.ofMillis(250), 4 * 1024 * 1024), mapper);
        delayMillis = 800;
        responseBody = "{}";
        assertThatThrownBy(() -> client.tokenize(UUID.randomUUID(), texts()))
                .isInstanceOfSatisfying(
                        AiClientException.class,
                        failure ->
                                assertThat(failure.kind())
                                        .isEqualTo(AiClientException.Kind.TIMEOUT));
        assertThat(posts.get()).isEqualTo(1);
        server.stop(0);
        assertThatThrownBy(client::ready)
                .isInstanceOfSatisfying(
                        AiClientException.class,
                        failure -> {
                            assertThat(failure.kind()).isEqualTo(AiClientException.Kind.TRANSPORT);
                            assertThat(failure.getCause()).isNull();
                            assertThat(failure.getMessage()).doesNotContain(TOKEN);
                        });
    }

    @Test
    void missingConfigurationFailsClosedWithoutPreventingConstructionOrLeakingSecret() {
        AiProperties configured = properties(Duration.ofSeconds(3), 4194304);
        assertThat(configured.toString()).doesNotContain(TOKEN, base.toString());
        AiProperties missing =
                new AiProperties(base, "", Duration.ofSeconds(3), configured.timeouts());
        AiInternalClient noConfig = new AiInternalClient(missing, mapper);
        assertThatThrownBy(noConfig::ready)
                .isInstanceOfSatisfying(
                        AiClientException.class,
                        failure ->
                                assertThat(failure.kind())
                                        .isEqualTo(AiClientException.Kind.CONFIGURATION));
        assertThatThrownBy(() -> noConfig.embed(UUID.randomUUID(), texts()))
                .isInstanceOfSatisfying(
                        AiClientException.class,
                        failure ->
                                assertThat(failure.kind())
                                        .isEqualTo(AiClientException.Kind.CONFIGURATION));
        assertThat(posts.get()).isZero();
        assertThat(gets.get()).isZero();
    }

    private AiProperties properties(Duration operationTimeout, int maxBytes) {
        return new AiProperties(
                base,
                TOKEN,
                Duration.ofSeconds(3),
                new AiProperties.Timeouts(
                        operationTimeout,
                        operationTimeout,
                        operationTimeout,
                        operationTimeout,
                        operationTimeout,
                        operationTimeout),
                maxBytes,
                EMBED,
                TOKENIZER,
                RERANK);
    }

    private ReadyOutput readyOutput(boolean embed, boolean rerank) {
        return new ReadyOutput(
                embed && rerank ? ReadyStatus.READY : ReadyStatus.NOT_READY,
                List.of(
                        new ModelState("tokenizer:kiwipiepy", TOKENIZER, true),
                        new ModelState(
                                embed ? "embed:BAAI/bge-m3" : "embed:off",
                                embed ? EMBED : null,
                                embed),
                        new ModelState(
                                rerank ? "rerank:BAAI/bge-reranker-v2-m3" : "rerank:off",
                                rerank ? RERANK : null,
                                rerank)));
    }

    private List<IdText> texts() {
        return List.of(new IdText(ID1, "검수용 합성 문장"));
    }

    private List<RerankCandidate> candidates() {
        return List.of(new RerankCandidate(ID1, "후보 일"), new RerankCandidate(ID2, "후보 이"));
    }

    private List<Double> vector() {
        List<Double> result = new ArrayList<>(Collections.nCopies(1024, 0.0));
        result.set(0, 1.0);
        return result;
    }

    private String json(Object object) {
        try {
            return mapper.writeValueAsString(object);
        } catch (Exception e) {
            throw new IllegalStateException("Synthetic fixture serialization failed");
        }
    }

    private void assertProtocol(Runnable operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(
                        AiClientException.class,
                        failure ->
                                assertThat(failure.kind())
                                        .isEqualTo(AiClientException.Kind.PROTOCOL));
    }

    private void assertInput(Runnable operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(
                        AiClientException.class,
                        failure ->
                                assertThat(failure.kind())
                                        .isEqualTo(AiClientException.Kind.INPUT_INVALID));
    }

    private void respond(HttpExchange exchange) throws IOException {
        boolean readiness = exchange.getRequestURI().getPath().endsWith("/health/ready");
        if (readiness) gets.incrementAndGet();
        else posts.incrementAndGet();
        String requestId = exchange.getRequestHeaders().getFirst("X-Request-Id");
        if (!readiness) {
            observedPath = exchange.getRequestURI().getPath();
            observedToken = exchange.getRequestHeaders().getFirst("X-Internal-Token");
            observedRequestId = requestId;
            observedContract = exchange.getRequestHeaders().getFirst("X-Contract-Version");
            observedBody =
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (delayMillis > 0)
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
        }
        int status = readiness ? readyStatus : responseStatus;
        String body = readiness ? readyBody : responseBody;
        if (body != null && body.startsWith("DYNAMIC_ERROR:"))
            body =
                    json(
                            Map.of(
                                    "code",
                                    body.substring("DYNAMIC_ERROR:".length()),
                                    "message",
                                    "sensitive remote input",
                                    "requestId",
                                    requestId,
                                    "timestamp",
                                    "2026-10-14T10:00:00Z",
                                    "retryable",
                                    true));
        if (body != null) body = body.replace("\"code\":\"NULL_CODE\"", "\"code\":null");
        byte[] bytes = (body == null ? "{}" : body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders()
                .set("Content-Type", readiness ? "application/json" : responseContentType);
        if (status == 302) exchange.getResponseHeaders().set("Location", base + "/tokenize");
        try {
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        } finally {
            exchange.close();
        }
    }
}
