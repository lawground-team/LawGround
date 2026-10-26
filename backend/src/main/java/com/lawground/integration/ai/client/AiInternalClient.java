package com.lawground.integration.ai.client;

import static com.lawground.integration.ai.client.AiClientException.Kind.*;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.lawground.integration.ai.config.AiProperties;
import com.lawground.integration.ai.dto.AiDtos.*;
import io.netty.channel.ChannelOption;
import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

/**
 * Synchronous adapter for Spring workers; no HTTP or transport auto retry, persistence or LLM
 * calls.
 */
@Component
public class AiInternalClient {
    public static final String CONTRACT_VERSION = "0.1.0-draft";

    public enum Capability {
        TOKENIZE,
        EMBED,
        RERANK
    }

    private final AiProperties properties;
    private final ObjectMapper mapper;

    public AiInternalClient(AiProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper.copy();
        this.mapper.enable(
                DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS,
                DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
                DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES);
        this.mapper.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        this.mapper.disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
        this.mapper.configure(MapperFeature.ALLOW_COERCION_OF_SCALARS, false);
        for (LogicalType type :
                List.of(
                        LogicalType.Textual,
                        LogicalType.Integer,
                        LogicalType.Float,
                        LogicalType.Boolean)) {
            for (CoercionInputShape shape :
                    List.of(
                            CoercionInputShape.String,
                            CoercionInputShape.Integer,
                            CoercionInputShape.Float,
                            CoercionInputShape.Boolean,
                            CoercionInputShape.EmptyString)) {
                boolean nativeShape =
                        type == LogicalType.Textual && shape == CoercionInputShape.String
                                || type == LogicalType.Integer
                                        && shape == CoercionInputShape.Integer
                                || type == LogicalType.Float
                                        && (shape == CoercionInputShape.Integer
                                                || shape == CoercionInputShape.Float)
                                || type == LogicalType.Boolean
                                        && shape == CoercionInputShape.Boolean;
                if (!nativeShape)
                    this.mapper.coercionConfigFor(type).setCoercion(shape, CoercionAction.Fail);
            }
        }
    }

    public ReadyOutput ready() {
        return ready(UUID.randomUUID(), transportTimeout());
    }

    /** A globally NOT_READY service can still support one genuinely ready, pinned capability. */
    public void requireReady(ReadyOutput output, Capability... capabilities) {
        validReady(output);
        if (capabilities == null || capabilities.length == 0) throw input();
        for (Capability capability : capabilities) {
            if (capability == null) throw input();
            String name =
                    switch (capability) {
                        case TOKENIZE -> "tokenizer:kiwipiepy";
                        case EMBED -> "embed:BAAI/bge-m3";
                        case RERANK -> "rerank:BAAI/bge-reranker-v2-m3";
                    };
            String approved = revision(capability);
            ModelState model =
                    output.models().stream()
                            .filter(m -> name.equals(m.name()))
                            .findFirst()
                            .orElse(null);
            if (model == null || !model.ready())
                throw failure(NOT_READY, "CAPABILITY_NOT_READY", false);
            if (!approved.equals(model.revision()))
                throw failure(VERSION_MISMATCH, "MODEL_REVISION_MISMATCH", false);
        }
    }

    public TokenizeResponse tokenize(UUID requestId, List<IdText> texts) {
        validTexts(requestId, texts);
        String revision = revision(Capability.TOKENIZE);
        long deadline = deadline(timeout(Capability.TOKENIZE));
        requireReady(ready(requestId, remaining(deadline)), Capability.TOKENIZE);
        TokenizeResponse out =
                call(
                        "tokenize",
                        requestId,
                        new TokenizeRequest(requestId, texts, revision),
                        TokenizeResponse.class,
                        remaining(deadline));
        if (!revision.equals(out.tokenizerVersion())
                || out.items() == null
                || out.items().size() != texts.size()) throw protocol();
        for (int i = 0; i < texts.size(); i++) {
            TokenItem item = out.items().get(i);
            if (item == null
                    || !texts.get(i).id().equals(item.id())
                    || item.tokens() == null
                    || item.tokens().size() > 10000) throw protocol();
            for (String token : item.tokens()) if (!text(token, 200)) throw protocol();
        }
        return out;
    }

    public EmbedResponse embed(UUID requestId, List<IdText> texts) {
        validTexts(requestId, texts);
        String revision = revision(Capability.EMBED);
        long deadline = deadline(timeout(Capability.EMBED));
        requireReady(ready(requestId, remaining(deadline)), Capability.EMBED);
        EmbedResponse out =
                call(
                        "embed",
                        requestId,
                        new EmbedRequest(requestId, texts, revision),
                        EmbedResponse.class,
                        remaining(deadline));
        if (!revision.equals(out.modelRevision())
                || out.dim() != 1024
                || out.items() == null
                || out.items().size() != texts.size()) throw protocol();
        for (int i = 0; i < texts.size(); i++) {
            EmbedItem item = out.items().get(i);
            if (item == null
                    || !texts.get(i).id().equals(item.id())
                    || item.vector() == null
                    || item.vector().size() != 1024) throw protocol();
            boolean nonzero = false;
            for (Double value : item.vector()) {
                if (value == null || !Double.isFinite(value)) throw protocol();
                if (value != 0) nonzero = true;
            }
            if (!nonzero) throw protocol();
        }
        return out;
    }

    public RerankResponse rerank(
            UUID requestId, String query, List<RerankCandidate> candidates, int topK) {
        if (requestId == null
                || !text(query, 30000)
                || candidates == null
                || candidates.isEmpty()
                || candidates.size() > 30
                || topK < 1
                || topK > 5) throw input();
        Map<UUID, Integer> positions = new HashMap<>();
        for (int i = 0; i < candidates.size(); i++) {
            RerankCandidate candidate = candidates.get(i);
            if (candidate == null
                    || candidate.chunkId() == null
                    || !text(candidate.text(), 30000)
                    || positions.put(candidate.chunkId(), i) != null) throw input();
        }
        String revision = revision(Capability.RERANK);
        long deadline = deadline(timeout(Capability.RERANK));
        requireReady(ready(requestId, remaining(deadline)), Capability.RERANK);
        RerankResponse out =
                call(
                        "rerank",
                        requestId,
                        new RerankRequest(requestId, query, candidates, topK),
                        RerankResponse.class,
                        remaining(deadline));
        if (!revision.equals(out.modelRevision())
                || out.ranked() == null
                || out.ranked().isEmpty()
                || out.ranked().size() > Math.min(topK, candidates.size())) throw protocol();
        Set<UUID> seen = new HashSet<>();
        RerankItem previous = null;
        for (RerankItem item : out.ranked()) {
            if (item == null
                    || !positions.containsKey(item.chunkId())
                    || !seen.add(item.chunkId())
                    || !Double.isFinite(item.score())) throw protocol();
            if (previous != null
                    && (previous.score() < item.score()
                            || previous.score() == item.score()
                                    && positions.get(previous.chunkId())
                                            > positions.get(item.chunkId()))) throw protocol();
            previous = item;
        }
        return out;
    }

    private ReadyOutput ready(UUID requestId, Duration timeout) {
        ReadyOutput output =
                exchange("health/ready", requestId, null, ReadyOutput.class, timeout, true);
        validReady(output);
        return output;
    }

    private <T> T call(String path, UUID requestId, Object body, Class<T> type, Duration timeout) {
        return exchange(path, requestId, body, type, timeout, false);
    }

    private <T> T exchange(
            String path,
            UUID requestId,
            Object body,
            Class<T> type,
            Duration timeout,
            boolean readiness) {
        URI uri = endpoint(path);
        int maxBytes = properties.maxResponseBytes();
        if (maxBytes < 1024 || maxBytes > 16 * 1024 * 1024) throw configuration();
        Duration connect = transportTimeout();
        HttpClient transport =
                HttpClient.create()
                        .followRedirect(false)
                        .disableRetry(true)
                        .option(
                                ChannelOption.CONNECT_TIMEOUT_MILLIS,
                                Math.toIntExact(connect.toMillis()));
        WebClient client =
                WebClient.builder()
                        .clientConnector(new ReactorClientHttpConnector(transport))
                        .codecs(c -> c.defaultCodecs().maxInMemorySize(maxBytes))
                        .build();
        try {
            WebClient.RequestBodySpec spec =
                    client.method(body == null ? HttpMethod.GET : HttpMethod.POST)
                            .uri(uri)
                            .header("X-Internal-Token", properties.apiKey())
                            .header("X-Request-Id", requestId.toString())
                            .header("X-Contract-Version", CONTRACT_VERSION)
                            .accept(MediaType.APPLICATION_JSON);
            WebClient.RequestHeadersSpec<?> request =
                    body == null
                            ? spec
                            : spec.contentType(MediaType.APPLICATION_JSON)
                                    .bodyValue(mapper.writeValueAsBytes(body));
            return request.exchangeToMono(
                            response -> {
                                int status = response.statusCode().value();
                                if (response.headers()
                                        .contentType()
                                        .filter(t -> MediaType.APPLICATION_JSON.isCompatibleWith(t))
                                        .isEmpty())
                                    return response.releaseBody().then(Mono.error(protocol()));
                                return response.bodyToMono(byte[].class)
                                        .defaultIfEmpty(new byte[0])
                                        .map(
                                                bytes -> {
                                                    if (bytes.length == 0
                                                            || bytes.length > maxBytes)
                                                        throw protocol();
                                                    if (status == 200
                                                            || readiness && status == 503) {
                                                        T out = decode(bytes, type);
                                                        if (readiness
                                                                && ((ReadyOutput) out).status()
                                                                        != (status == 200
                                                                                ? ReadyStatus.READY
                                                                                : ReadyStatus
                                                                                        .NOT_READY))
                                                            throw protocol();
                                                        return out;
                                                    }
                                                    throw remote(
                                                            status,
                                                            decode(bytes, ErrorOutput.class),
                                                            requestId);
                                                });
                            })
                    .timeout(timeout)
                    .block();
        } catch (AiClientException safe) {
            throw safe;
        } catch (Exception unsafe) {
            Throwable cause = unsafe;
            for (int depth = 0; cause != null && depth < 10; depth++, cause = cause.getCause()) {
                if (cause instanceof TimeoutException
                        || cause instanceof io.netty.handler.timeout.TimeoutException)
                    throw failure(TIMEOUT, "DEADLINE_EXCEEDED", true);
                if (cause instanceof DataBufferLimitException) throw protocol();
            }
            throw failure(TRANSPORT, "CONNECTION_FAILED", true);
        }
    }

    private <T> T decode(byte[] bytes, Class<T> type) {
        try {
            T output = mapper.readValue(bytes, type);
            if (output == null) throw protocol();
            return output;
        } catch (AiClientException safe) {
            throw safe;
        } catch (Exception ignored) {
            throw protocol();
        }
    }

    private AiClientException remote(int status, ErrorOutput error, UUID expectedId) {
        if (!expectedId.equals(error.requestId())
                || error.timestamp() == null
                || !text(error.code(), 80)
                || !text(error.message(), 500)) throw protocol();
        Set<String> allowed =
                switch (status) {
                    case 400 -> Set.of("INVALID_REQUEST", "UNSUPPORTED_CONTRACT_VERSION");
                    case 401 -> Set.of("UNAUTHORIZED");
                    case 409 -> Set.of("MODEL_REVISION_MISMATCH", "IDEMPOTENCY_CONFLICT");
                    case 413 -> Set.of("PAYLOAD_TOO_LARGE");
                    case 422 -> Set.of("INVALID_REQUEST", "INPUT_TOO_LONG");
                    case 429 -> Set.of("RATE_LIMITED");
                    case 500 -> Set.of("INTERNAL_ERROR", "MODEL_OUTPUT_INVALID");
                    case 502 -> Set.of("MODEL_UPSTREAM_ERROR");
                    case 503 -> Set.of("MODEL_NOT_READY");
                    case 504 -> Set.of("MODEL_TIMEOUT");
                    default -> Set.of();
                };
        if (!allowed.contains(error.code())) throw protocol();
        boolean retryable = error.retryable() && (status == 429 || status == 502 || status == 504);
        return new AiClientException(REMOTE_ERROR, status, error.code(), retryable);
    }

    private void validReady(ReadyOutput output) {
        if (output == null
                || output.status() == null
                || output.models() == null
                || output.models().isEmpty()
                || output.models().size() > 20) throw protocol();
        Set<String> names = new HashSet<>();
        Set<String> capabilities = new HashSet<>();
        boolean allReady = true;
        for (ModelState model : output.models()) {
            if (model == null
                    || !text(model.name(), 200)
                    || !names.add(model.name())
                    || model.revision() != null && !text(model.revision(), 160)) throw protocol();
            String prefix = model.name().split(":", 2)[0];
            if (Set.of("tokenizer", "embed", "rerank").contains(prefix)
                    && !capabilities.add(prefix)) throw protocol();
            if (model.ready() && model.revision() == null) throw protocol();
            allReady &= model.ready();
        }
        if ((output.status() == ReadyStatus.READY) != allReady) throw protocol();
    }

    private void validTexts(UUID requestId, List<IdText> texts) {
        if (requestId == null || texts == null || texts.isEmpty() || texts.size() > 32)
            throw input();
        Set<UUID> ids = new HashSet<>();
        for (IdText item : texts)
            if (item == null
                    || item.id() == null
                    || !ids.add(item.id())
                    || !text(item.text(), 30000)) throw input();
    }

    private String revision(Capability capability) {
        String value =
                switch (capability) {
                    case TOKENIZE -> properties.tokenizerVersion();
                    case EMBED -> properties.embeddingModelRevision();
                    case RERANK -> properties.rerankerModelRevision();
                };
        if (!text(value, 160)
                || capability != Capability.TOKENIZE && !value.matches("[a-f0-9]{40}"))
            throw configuration();
        return value;
    }

    private URI endpoint(String path) {
        URI base = properties.baseUrl();
        if (base == null
                || !("http".equals(base.getScheme()) || "https".equals(base.getScheme()))
                || base.getHost() == null
                || base.getRawUserInfo() != null
                || base.getRawQuery() != null
                || base.getRawFragment() != null
                || !text(properties.apiKey(), 4096)
                || properties.apiKey().contains("\r")
                || properties.apiKey().contains("\n")) throw configuration();
        String value = base.toASCIIString();
        return URI.create(value + (value.endsWith("/") ? "" : "/") + path);
    }

    private Duration transportTimeout() {
        Duration duration = properties.connectTimeout();
        if (duration == null || duration.toMillis() < 1 || duration.toMillis() > Integer.MAX_VALUE)
            throw configuration();
        return duration;
    }

    private Duration timeout(Capability capability) {
        if (properties.timeouts() == null) throw configuration();
        Duration value =
                switch (capability) {
                    case TOKENIZE -> properties.timeouts().tokenize();
                    case EMBED -> properties.timeouts().embed();
                    case RERANK -> properties.timeouts().rerank();
                };
        if (value == null
                || value.isZero()
                || value.isNegative()
                || value.compareTo(Duration.ofMinutes(30)) > 0) throw configuration();
        return value;
    }

    private static boolean text(String value, int max) {
        return value != null && !value.isBlank() && value.codePointCount(0, value.length()) <= max;
    }

    private static long deadline(Duration timeout) {
        return System.nanoTime() + timeout.toNanos();
    }

    private static Duration remaining(long deadline) {
        long nanos = deadline - System.nanoTime();
        if (nanos <= 0) throw failure(TIMEOUT, "DEADLINE_EXCEEDED", true);
        return Duration.ofNanos(nanos);
    }

    private static AiClientException configuration() {
        return failure(CONFIGURATION, "CONFIGURATION_REQUIRED", false);
    }

    private static AiClientException input() {
        return failure(INPUT_INVALID, "INVALID_REQUEST", false);
    }

    private static AiClientException protocol() {
        return failure(PROTOCOL, "INVALID_RESPONSE", false);
    }

    private static AiClientException failure(
            AiClientException.Kind kind, String code, boolean retryable) {
        return new AiClientException(kind, 0, code, retryable);
    }
}
