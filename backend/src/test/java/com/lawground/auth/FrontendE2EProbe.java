package com.lawground.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lawground.LawGroundApplication;
import com.lawground.integration.ai.support.VerifierTestServer;
import com.lawground.law.dto.LawImport;
import com.lawground.law.search.LiveLawIndexService;
import com.lawground.law.service.LawImportService;
import com.lawground.question.dto.CreateQuestionRequest;
import com.lawground.question.service.QuestionService;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.registration.*;
import org.testcontainers.containers.*;
import org.testcontainers.utility.DockerImageName;

/**
 * Opt-in browser probe. All issuers, controls, fixtures and containers exist only in test sources.
 */
public final class FrontendE2EProbe {
    static TestOidcProvider issuer;
    static volatile String mode = "supported";
    static volatile CountDownLatch gate = new CountDownLatch(0);

    @TestConfiguration(proxyBeanMethods = false)
    static class OidcConfiguration {
        @Bean
        @Primary
        ClientRegistrationRepository registration() {
            return new InMemoryClientRegistrationRepository(issuer.registration());
        }
    }

    static ObjectNode result(ObjectNode n) {
        var choices = n.putArray("choiceAnalyses");
        boolean hold = mode.equals("hold");
        n.put("verdict", hold ? "HOLD" : mode.equals("rejected") ? "REJECTED" : "SUPPORTED");
        if (hold) n.putArray("evidences");
        for (int i = 1; i <= 5; i++) {
            var c =
                    choices.addObject()
                            .put("choiceNumber", i)
                            .put(
                                    "relation",
                                    hold ? "INSUFFICIENT" : i == 2 ? "CONTRADICTS" : "SUPPORTS")
                            .put(
                                    "explanation",
                                    hold
                                            ? "합성 검수 응답에서 판단을 보류합니다."
                                            : "합성 HTTP로 선지와 인용 연결을 검사한 설명입니다.");
            if (!hold && i == 2) c.put("correctedText", "합성 검수용 정정문이며 실제 법률 해설이 아닙니다.");
            else c.putNull("correctedText");
            c.putArray("tags");
            var refs = c.putArray("evidenceKeys");
            if (!hold) refs.add("e1");
        }
        return n;
    }

    public static void main(String[] args) throws Exception {
        String lawId = UUID.randomUUID().toString(), controlToken = UUID.randomUUID().toString();
        var pg =
                new PostgreSQLContainer<>(
                        DockerImageName.parse("pgvector/pgvector:0.8.2-pg16")
                                .asCompatibleSubstituteFor("postgres"));
        var rabbit = new RabbitMQContainer("rabbitmq:4.2.9-management");
        try (pg;
                rabbit;
                var provider = new TestOidcProvider();
                var http = new VerifierTestServer()) {
            issuer = provider;
            pg.start();
            rabbit.start();
            http.transform = FrontendE2EProbe::result;
            http.onVerify =
                    () -> {
                        if (mode.equals("wait"))
                            try {
                                if (!gate.await(60, TimeUnit.SECONDS))
                                    throw new IllegalStateException("Probe gate expired");
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                throw new IllegalStateException(e);
                            }
                    };
            try (var context =
                    new SpringApplicationBuilder(
                                    LawGroundApplication.class, OidcConfiguration.class)
                            .profiles("test")
                            .run(
                                    "--server.port=0",
                                    "--server.address=127.0.0.1",
                                    "--spring.datasource.url=" + pg.getJdbcUrl(),
                                    "--spring.datasource.username=" + pg.getUsername(),
                                    "--spring.datasource.password=" + pg.getPassword(),
                                    "--spring.rabbitmq.host=" + rabbit.getHost(),
                                    "--spring.rabbitmq.port=" + rabbit.getAmqpPort(),
                                    "--spring.rabbitmq.username=" + rabbit.getAdminUsername(),
                                    "--spring.rabbitmq.password=" + rabbit.getAdminPassword(),
                                    "--lawground.security.oidc.enabled=true",
                                    "--lawground.security.oidc.client-id=synthetic-client",
                                    "--lawground.security.oidc.client-secret=synthetic-secret",
                                    "--lawground.security.oidc.redirect-uri=http://127.0.0.1:8080/login/oauth2/code/google",
                                    "--lawground.analysis.live.enabled=true",
                                    "--lawground.analysis.verification.enabled=true",
                                    "--lawground.analysis.listener-enabled=true",
                                    "--lawground.analysis.mock-enabled=false",
                                    "--lawground.analysis.live.law-ids=" + lawId,
                                    "--lawground.ui.analysis-enabled=true",
                                    "--lawground.ai.base-url=" + http.url(),
                                    "--lawground.ai.api-key=" + VerifierTestServer.KEY,
                                    "--lawground.ai.embedding-model-revision="
                                            + VerifierTestServer.MODEL,
                                    "--lawground.ai.tokenizer-version="
                                            + VerifierTestServer.TOKENIZER,
                                    "--lawground.ai.reranker-model-revision="
                                            + VerifierTestServer.RERANK,
                                    "--lawground.analysis.verification.model-name="
                                            + VerifierTestServer.NAME,
                                    "--lawground.analysis.verification.model-revision="
                                            + VerifierTestServer.VERIFIER,
                                    "--lawground.analysis.verification.prompt-version="
                                            + VerifierTestServer.PROMPT)) {
                var jdbc = context.getBean(JdbcTemplate.class);
                String source = "alpha 합성 브라우저 검수 원문입니다. 실제 법령이 아닙니다.";
                jdbc.update(
                        "INSERT INTO laws(id,source_provider,source_law_id,title) VALUES (?,'LAW_GO_KR','SYNTHETIC-BROWSER','합성 검수 법령')",
                        UUID.fromString(lawId));
                var imported =
                        context.getBean(LawImportService.class)
                                .importVersion(
                                        new LawImport(
                                                "LAW_GO_KR",
                                                "SYNTHETIC-BROWSER",
                                                "v1",
                                                "합성 검수 법령",
                                                LocalDate.of(2024, 1, 1),
                                                null,
                                                "VERIFIED",
                                                "https://www.law.go.kr/TEST-ONLY",
                                                Map.of("synthetic", true),
                                                List.of(
                                                        new LawImport.Article(
                                                                "a1",
                                                                "합성 제1조",
                                                                source,
                                                                List.of(
                                                                        new LawImport.Span(
                                                                                0,
                                                                                source
                                                                                        .codePointCount(
                                                                                                0,
                                                                                                source
                                                                                                        .length()),
                                                                                source))))));
                context.getBean(LiveLawIndexService.class)
                        .indexChunk(imported.chunkIds().getFirst());
                UUID owner = UUID.randomUUID();
                jdbc.update(
                        "INSERT INTO members(id,display_name,status) VALUES (?,'합성 기출 공급자','ACTIVE')",
                        owner);
                UUID publicQuestion;
                var security = SecurityContextHolder.createEmptyContext();
                security.setAuthentication(
                        new UsernamePasswordAuthenticationToken(owner.toString(), null, List.of()));
                SecurityContextHolder.setContext(security);
                try {
                    publicQuestion =
                            context.getBean(QuestionService.class)
                                    .create(
                                            new CreateQuestionRequest(
                                                    CreateQuestionRequest.Subject.BROKER_LAW,
                                                    "합성 공개 기출 검수 문제",
                                                    CreateQuestionRequest.QuestionType
                                                            .SELECT_INCORRECT,
                                                    java.util.stream.IntStream.rangeClosed(1, 5)
                                                            .mapToObj(
                                                                    i ->
                                                                            new CreateQuestionRequest
                                                                                    .Choice(
                                                                                    i,
                                                                                    "합성 공개 선지 "
                                                                                            + i))
                                                            .toList(),
                                                    2,
                                                    LocalDate.of(2025, 10, 25)))
                                    .id();
                } finally {
                    SecurityContextHolder.clearContext();
                }
                jdbc.update(
                        "UPDATE questions SET visibility='PUBLIC',source_kind='EXAM_IMPORT',answer_source='OFFICIAL_VERIFIED',source_key='SYNTHETIC-BROWSER-1',source_url='https://example.test/synthetic-exam' WHERE id=?",
                        publicQuestion);
                var json = context.getBean(ObjectMapper.class);
                var control = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                control.setExecutor(
                        Executors.newCachedThreadPool(
                                r -> {
                                    var t = new Thread(r);
                                    t.setDaemon(true);
                                    return t;
                                }));
                control.createContext(
                        "/",
                        x -> {
                            if (!controlToken.equals(
                                    x.getRequestHeaders().getFirst("X-Probe-Token"))) {
                                x.sendResponseHeaders(403, -1);
                                x.close();
                                return;
                            }
                            var in = json.readTree(x.getRequestBody());
                            String command = in.path("command").asText();
                            if (command.equals("identity"))
                                issuer.subject = in.path("subject").asText();
                            else if (command.equals("mode")) {
                                gate.countDown();
                                mode = in.path("mode").asText();
                                gate = new CountDownLatch(mode.equals("wait") ? 1 : 0);
                                http.verifyStatus = mode.equals("fail") ? 503 : 0;
                            } else if (command.equals("release")) {
                                mode = "supported";
                                gate.countDown();
                            }
                            byte[] out = json.writeValueAsBytes(Map.of("ok", true));
                            x.getResponseHeaders().set("Content-Type", "application/json");
                            x.sendResponseHeaders(200, out.length);
                            x.getResponseBody().write(out);
                            x.close();
                        });
                control.start();
                try {
                    int port =
                            ((ServletWebServerApplicationContext) context).getWebServer().getPort();
                    var directory = Path.of("build/browser-e2e").toAbsolutePath();
                    Files.createDirectories(directory);
                    var node = System.getenv().getOrDefault("LAWGROUND_E2E_NODE", "node");
                    var process =
                            new ProcessBuilder(
                                    node,
                                    Path.of("../frontend/tests/e2e.mjs")
                                            .toAbsolutePath()
                                            .toString());
                    var env = process.environment();
                    env.put("LAWGROUND_E2E_BASE", "http://127.0.0.1:" + port);
                    env.put(
                            "LAWGROUND_E2E_CONTROL",
                            "http://127.0.0.1:" + control.getAddress().getPort());
                    env.put("LAWGROUND_E2E_TOKEN", controlToken);
                    env.put("LAWGROUND_E2E_PUBLIC_QUESTION", publicQuestion.toString());
                    env.put("LAWGROUND_E2E_OUTPUT", directory.toString());
                    var child = process.inheritIO().start();
                    if (!child.waitFor(240, TimeUnit.SECONDS)) {
                        child.destroyForcibly();
                        throw new IllegalStateException("Browser probe timeout");
                    }
                    if (child.exitValue() != 0)
                        throw new IllegalStateException("Browser probe failed");
                    if (!http.violations.isEmpty())
                        throw new IllegalStateException("Synthetic protocol violations");
                    System.out.println(
                            "PASS browser/real DB/queue/signed test OIDC; synthetic inference only");
                } finally {
                    gate.countDown();
                    control.stop(0);
                }
            }
        }
    }
}
