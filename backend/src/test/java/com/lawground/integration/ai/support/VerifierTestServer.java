package com.lawground.integration.ai.support;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lawground.integration.ai.dto.AiDtos.*;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.UnaryOperator;

/** Synthetic inference only. Shared real HTTP fixture for client and infrastructure tests. */
public final class VerifierTestServer implements AutoCloseable {
    public static final String MODEL = "a".repeat(40),
            RERANK = "b".repeat(40),
            VERIFIER = "c".repeat(40),
            PROMPT = "verify-core-v1",
            NAME = "verify:contract-test",
            TOKENIZER = "kiwipiepy-0.24.0+rules-1",
            KEY = "synthetic-verifier-http-token";
    public final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    public final List<String> posts = new CopyOnWriteArrayList<>(),
            violations = new CopyOnWriteArrayList<>();
    public final List<JsonNode> verifyRequests = new CopyOnWriteArrayList<>();
    public volatile String readiness = "ready";
    public volatile int verifyStatus;
    public volatile String retryAfter;
    public volatile Runnable onVerify = () -> {};
    public volatile UnaryOperator<ObjectNode> transform = UnaryOperator.identity();
    private final HttpServer server;

    public VerifierTestServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(
                    Executors.newCachedThreadPool(
                            r -> {
                                var t = new Thread(r);
                                t.setDaemon(true);
                                return t;
                            }));
            server.createContext("/internal/v1/", this::handle);
            server.start();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/internal/v1";
    }

    public void reset() {
        readiness = "ready";
        verifyStatus = 0;
        retryAfter = null;
        onVerify = () -> {};
        transform = UnaryOperator.identity();
        posts.clear();
        violations.clear();
        verifyRequests.clear();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath().substring("/internal/v1/".length());
        if (!KEY.equals(exchange.getRequestHeaders().getFirst("X-Internal-Token")))
            violations.add("token");
        if (!"0.1.0-draft".equals(exchange.getRequestHeaders().getFirst("X-Contract-Version")))
            violations.add("contract");
        int status = 200;
        Object out;
        if (path.equals("health/ready")) {
            var models =
                    new ArrayList<>(
                            List.of(
                                    new ModelState("tokenizer:kiwipiepy", TOKENIZER, true),
                                    new ModelState("embed:BAAI/bge-m3", MODEL, true),
                                    new ModelState(
                                            "rerank:BAAI/bge-reranker-v2-m3", RERANK, true)));
            if (!readiness.equals("missing"))
                models.add(
                        new ModelState(
                                readiness.equals("mock") ? "verify:mock" : NAME,
                                readiness.equals("revision") ? "d".repeat(40) : VERIFIER,
                                !readiness.equals("off")));
            if (readiness.equals("duplicate"))
                models.add(new ModelState("verify:other", VERIFIER, true));
            boolean ready = models.stream().allMatch(ModelState::ready);
            status = ready ? 200 : 503;
            out = new ReadyOutput(ready ? ReadyStatus.READY : ReadyStatus.NOT_READY, models);
        } else {
            var in = json.readTree(exchange.getRequestBody());
            posts.add(path);
            if (!in.path("requestId")
                    .asText()
                    .equals(exchange.getRequestHeaders().getFirst("X-Request-Id")))
                violations.add("request-id");
            if (path.equals("verify")) {
                verifyRequests.add(in);
                onVerify.run();
                status = verifyStatus == 0 ? 200 : verifyStatus;
                if (verifyStatus != 0)
                    out =
                            Map.of(
                                    "code",
                                    switch (verifyStatus) {
                                        case 429 -> "RATE_LIMITED";
                                        case 502 -> "MODEL_UPSTREAM_ERROR";
                                        case 504 -> "MODEL_TIMEOUT";
                                        default -> "MODEL_NOT_READY";
                                    },
                                    "message",
                                    "synthetic failure",
                                    "requestId",
                                    in.path("requestId").asText(),
                                    "timestamp",
                                    "2026-10-26T00:00:00Z",
                                    "retryable",
                                    verifyStatus != 503);
                else {
                    var node =
                            json.createObjectNode()
                                    .put("verdict", "SUPPORTED")
                                    .put("reasonCode", "SUPPORTED_BY_SOURCE")
                                    .put("summary", "합성 연결 검사 결과")
                                    .putNull("choiceAnalyses")
                                    .putNull("tagSetVersion")
                                    .put("modelRevision", VERIFIER)
                                    .put("promptVersion", PROMPT);
                    String source = in.at("/candidates/0/sourceText").asText();
                    node.putArray("evidences")
                            .addObject()
                            .put("evidenceKey", "e1")
                            .put("chunkId", in.at("/candidates/0/chunkId").asText())
                            .put("quote", source)
                            .put("start", 0)
                            .put("end", source.codePointCount(0, source.length()));
                    out = transform.apply(node);
                }
            } else if (path.equals("tokenize")) {
                var items = new ArrayList<TokenItem>();
                for (var text : in.path("texts"))
                    items.add(
                            new TokenItem(
                                    UUID.fromString(text.path("id").asText()), List.of("alpha")));
                out = new TokenizeResponse(items, TOKENIZER);
            } else if (path.equals("embed")) {
                var items = new ArrayList<EmbedItem>();
                for (var text : in.path("texts")) {
                    var v = new ArrayList<>(Collections.nCopies(1024, 0.0));
                    v.set(0, 1.0);
                    items.add(new EmbedItem(UUID.fromString(text.path("id").asText()), v));
                }
                out = new EmbedResponse(items, 1024, MODEL);
            } else if (path.equals("rerank")) {
                out =
                        new RerankResponse(
                                List.of(
                                        new RerankItem(
                                                UUID.fromString(
                                                        in.at("/candidates/0/chunkId").asText()),
                                                0.8)),
                                RERANK);
            } else {
                out = Map.of();
                status = 404;
            }
        }
        byte[] bytes = json.writeValueAsBytes(out);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        if (retryAfter != null) exchange.getResponseHeaders().set("Retry-After", retryAfter);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var stream = exchange.getResponseBody()) {
            stream.write(bytes);
        }
    }
}
