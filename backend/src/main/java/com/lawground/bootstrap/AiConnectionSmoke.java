package com.lawground.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lawground.integration.ai.client.AiClientException;
import com.lawground.integration.ai.client.AiInternalClient;
import com.lawground.integration.ai.client.AiInternalClient.Capability;
import com.lawground.integration.ai.dto.AiDtos;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Explicit local diagnostic only: synthetic inputs, no database reads/writes or public endpoint.
 */
@Component
@ConditionalOnProperty(name = "lawground.ai.smoke-enabled", havingValue = "true")
public class AiConnectionSmoke implements ApplicationRunner {
    private static final Logger LOG = LoggerFactory.getLogger(AiConnectionSmoke.class);
    private final AiInternalClient client;
    private final ObjectMapper mapper;
    private final Set<Capability> capabilities;

    public AiConnectionSmoke(
            AiInternalClient client,
            ObjectMapper mapper,
            Environment environment,
            @Value("${lawground.ai.smoke-capabilities:TOKENIZE,EMBED,RERANK}")
                    String capabilities) {
        validateEnvironment(environment);
        this.client = client;
        this.mapper = mapper;
        this.capabilities = parseCapabilities(capabilities);
    }

    static void validateEnvironment(Environment environment) {
        String address = environment.getProperty("server.address");
        if (!environment.matchesProfiles("local & !prod & !test")
                || address == null
                || !Set.of("127.0.0.1", "localhost", "::1").contains(address)) {
            throw new IllegalStateException(
                    "AI smoke requires the isolated local loopback profile");
        }
    }

    static Set<Capability> parseCapabilities(String value) {
        try {
            EnumSet<Capability> result = EnumSet.noneOf(Capability.class);
            for (String part : value.split(",", -1)) {
                Capability capability = Capability.valueOf(part.trim());
                if (!result.add(capability)) throw new IllegalArgumentException();
            }
            if (result.isEmpty()) throw new IllegalArgumentException();
            return Set.copyOf(result);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Invalid AI smoke capabilities");
        }
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("capabilities", capabilities.stream().map(Enum::name).sorted().toList());
        evidence.put("inputMode", "SYNTHETIC_CONNECTION_CHECK");
        String stage = "READY";
        try {
            var ready = client.ready();
            client.requireReady(ready, capabilities.toArray(Capability[]::new));
            evidence.put("requiredActualModelsReady", true);
            var texts =
                    List.of(
                            new AiDtos.IdText(
                                    UUID.randomUUID(), "[연결 검수용 합성 문장] 문서를 30일 동안 보관한다."));
            if (capabilities.contains(Capability.TOKENIZE)) {
                stage = "TOKENIZE";
                long started = System.nanoTime();
                var result = client.tokenize(UUID.randomUUID(), texts);
                evidence.put(
                        "tokenize",
                        Map.of(
                                "items",
                                result.items().size(),
                                "tokens",
                                result.items().getFirst().tokens().size(),
                                "version",
                                result.tokenizerVersion(),
                                "elapsedMs",
                                elapsed(started)));
            }
            if (capabilities.contains(Capability.EMBED)) {
                stage = "EMBED";
                long started = System.nanoTime();
                var result = client.embed(UUID.randomUUID(), texts);
                double norm =
                        Math.sqrt(
                                result.items().getFirst().vector().stream()
                                        .mapToDouble(v -> v * v)
                                        .sum());
                if (Math.abs(norm - 1.0) > 0.001)
                    throw new IllegalStateException("Unexpected BGE normalization");
                evidence.put(
                        "embed",
                        Map.of(
                                "items",
                                result.items().size(),
                                "dimension",
                                result.dim(),
                                "normalized",
                                true,
                                "revision",
                                result.modelRevision(),
                                "elapsedMs",
                                elapsed(started)));
            }
            if (capabilities.contains(Capability.RERANK)) {
                stage = "RERANK";
                long started = System.nanoTime();
                var candidates =
                        List.of(
                                new AiDtos.RerankCandidate(
                                        UUID.randomUUID(), "[합성] 문서 보관 기간은 30일이다."),
                                new AiDtos.RerankCandidate(
                                        UUID.randomUUID(), "[합성] 파일은 번호순으로 정리한다."),
                                new AiDtos.RerankCandidate(
                                        UUID.randomUUID(), "[합성] 담당자는 문서 보관 기간을 확인한다."),
                                new AiDtos.RerankCandidate(UUID.randomUUID(), "[합성] 회의 장소를 안내한다."));
                var result = client.rerank(UUID.randomUUID(), "문서 보관 기간", candidates, 2);
                evidence.put(
                        "rerank",
                        Map.of(
                                "candidates",
                                candidates.size(),
                                "returned",
                                result.ranked().size(),
                                "revision",
                                result.modelRevision(),
                                "elapsedMs",
                                elapsed(started)));
            }
            evidence.put("passed", true);
            LOG.info("AI_CONNECTION_SMOKE {}", mapper.writeValueAsString(evidence));
        } catch (RuntimeException failure) {
            // Do not attach remote exception messages, bodies, inputs or credentials to boot logs.
            if (failure instanceof AiClientException safe) {
                LOG.error(
                        "AI_CONNECTION_SMOKE_FAILED stage={} category={} code={} httpStatus={}",
                        stage,
                        safe.kind(),
                        safe.code(),
                        safe.httpStatus());
            } else {
                LOG.error("AI_CONNECTION_SMOKE_FAILED stage={} category=LOCAL_CHECK", stage);
            }
            throw new IllegalStateException("AI connection smoke failed at " + stage);
        }
    }

    private static long elapsed(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }
}
