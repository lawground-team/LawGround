package com.lawground.law.search;

import com.lawground.LawGroundApplication;
import com.lawground.integration.ai.client.AiInternalClient;
import com.lawground.integration.ai.client.AiInternalClient.Capability;
import com.lawground.law.dto.LawImport;
import com.lawground.law.service.LawImportService;
import java.time.LocalDate;
import java.util.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** Opt-in real-weight probe, not an ordinary test or a legal-accuracy benchmark. */
public final class LiveModelProbe {
    public static void main(String[] args) {
        String mode = args.length == 1 ? args[0] : "index";
        if (!List.of("index", "retrieval").contains(mode))
            throw new IllegalArgumentException("index/retrieval required");
        for (String key :
                List.of(
                        "AI_SERVICE_BASE_URL",
                        "AI_SERVICE_API_KEY",
                        "AI_EMBEDDING_MODEL_REVISION",
                        "AI_TOKENIZER_VERSION"))
            if (System.getenv(key) == null || System.getenv(key).isBlank())
                throw new IllegalArgumentException("Missing explicit probe setting: " + key);
        try (var postgres =
                new PostgreSQLContainer<>(
                        DockerImageName.parse("pgvector/pgvector:0.8.2-pg16")
                                .asCompatibleSubstituteFor("postgres"))) {
            postgres.start();
            try (var context =
                    new SpringApplicationBuilder(LawGroundApplication.class)
                            .profiles("test")
                            .run(
                                    "--server.address=127.0.0.1",
                                    "--server.port=0",
                                    "--spring.datasource.url=" + postgres.getJdbcUrl(),
                                    "--spring.datasource.username=" + postgres.getUsername(),
                                    "--spring.datasource.password=" + postgres.getPassword(),
                                    "--lawground.security.oidc.enabled=false",
                                    "--lawground.security.dev-auth.enabled=false",
                                    "--lawground.analysis.mock-enabled=false",
                                    "--springdoc.api-docs.enabled=false",
                                    "--springdoc.swagger-ui.enabled=false")) {
                var client = context.getBean(AiInternalClient.class);
                client.requireReady(
                        client.ready(),
                        mode.equals("retrieval")
                                ? new Capability[] {
                                    Capability.TOKENIZE, Capability.EMBED, Capability.RERANK
                                }
                                : new Capability[] {Capability.TOKENIZE, Capability.EMBED});
                String text = "합성 연결 검사 제1조 표본은 법령이나 법률 판정이 아닙니다.";
                var imported =
                        context.getBean(LawImportService.class)
                                .importVersion(
                                        new LawImport(
                                                "LAW_GO_KR",
                                                "SYNTHETIC-ISOLATED-PROBE-" + UUID.randomUUID(),
                                                "probe-1",
                                                "SYNTHETIC CONNECTION PROBE",
                                                LocalDate.of(2025, 1, 1),
                                                null,
                                                "VERIFIED",
                                                "https://www.law.go.kr/TEST-ONLY",
                                                Map.of(
                                                        "synthetic",
                                                        true,
                                                        "purpose",
                                                        "isolated model/DB acceptance"),
                                                List.of(
                                                        new LawImport.Article(
                                                                "test-1",
                                                                "SYNTHETIC",
                                                                text,
                                                                List.of(
                                                                        new LawImport.Span(
                                                                                0,
                                                                                text.codePointCount(
                                                                                        0,
                                                                                        text
                                                                                                .length()),
                                                                                text))))));
                var indexer = context.getBean(LiveLawIndexService.class);
                if (indexer.indexChunk(imported.chunkIds().getFirst())
                                != LiveLawIndexService.Outcome.INDEXED
                        || indexer.indexChunk(imported.chunkIds().getFirst())
                                != LiveLawIndexService.Outcome.REUSED)
                    throw new IllegalStateException("Index/reuse check failed");
                var jdbc = context.getBean(JdbcTemplate.class);
                UUID law =
                        jdbc.queryForObject(
                                "SELECT law_id FROM law_versions WHERE id=?",
                                UUID.class,
                                imported.versionId());
                var profile =
                        LiveSearchProfile.from(
                                context.getBean(
                                        com.lawground.integration.ai.config.AiProperties.class),
                                LawImportService.CHUNKER);
                var corpus =
                        context.getBean(LiveIndexStore.class)
                                .inspect(Set.of(law), LocalDate.of(2025, 1, 1), profile);
                if (corpus.availability() != LiveIndexStore.Availability.READY)
                    throw new IllegalStateException("Corpus not ready");
                if (mode.equals("retrieval")) {
                    var result =
                            context.getBean(LiveRetrievalService.class)
                                    .search(
                                            new LiveRetrievalService.Query(
                                                    Set.of(law),
                                                    LocalDate.of(2025, 1, 1),
                                                    text,
                                                    LawImportService.CHUNKER,
                                                    1,
                                                    1));
                    if (result.status() != LiveRetrievalService.Status.RETRIEVED
                            || result.hits().size() != 1
                            || result.verificationPerformed())
                        throw new IllegalStateException("Retrieval probe failed");
                }
                // Metadata only: no text, key, vector or generated legal verdict.
                System.out.println(
                        "LIVE_MODEL_PROBE {\"passed\":true,\"mode\":\""
                                + mode
                                + "\",\"isolatedDb\":true,\"syntheticInput\":true,\"indexReused\":true,\"corpusReady\":true,\"verificationPerformed\":false}");
            }
        }
    }
}
