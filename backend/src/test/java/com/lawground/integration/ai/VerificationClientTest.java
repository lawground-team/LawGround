package com.lawground.integration.ai;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lawground.integration.ai.client.*;
import com.lawground.integration.ai.config.*;
import com.lawground.integration.ai.dto.VerifyDtos.*;
import com.lawground.integration.ai.support.VerifierTestServer;
import java.net.URI;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class VerificationClientTest {
    VerifierTestServer server;
    AiInternalClient client;
    final VerificationProperties.Pin pin =
            new VerificationProperties.Pin(
                    VerifierTestServer.NAME,
                    VerifierTestServer.VERIFIER,
                    VerifierTestServer.PROMPT);

    @BeforeEach
    void setup() {
        server = new VerifierTestServer();
        client = client(Duration.ofSeconds(3));
    }

    @AfterEach
    void close() {
        assertThat(server.violations).isEmpty();
        server.close();
    }

    AiInternalClient client(Duration timeout) {
        return new AiInternalClient(
                new AiProperties(
                        URI.create(server.url()),
                        VerifierTestServer.KEY,
                        Duration.ofSeconds(1),
                        new AiProperties.Timeouts(
                                timeout, timeout, timeout, timeout, timeout, timeout)),
                server.json);
    }

    Request request() {
        var date = LocalDate.of(2025, 1, 1);
        var choices = new ArrayList<Choice>();
        for (int i = 1; i <= 5; i++) choices.add(new Choice(i, "합성 선지 " + i));
        return new Request(
                UUID.randomUUID(),
                new Snapshot("BROKER_LAW", "옳지 않은 것은?", "SELECT_INCORRECT", choices, 2, date, 1),
                date,
                List.of(
                        new Candidate(
                                UUID.randomUUID(),
                                "앞😀근거 문장\n뒤",
                                new Metadata(
                                        UUID.randomUUID(),
                                        UUID.randomUUID(),
                                        "SYNTHETIC",
                                        "제1조",
                                        LocalDate.of(2024, 1, 1),
                                        null))));
    }

    @Test
    void preservesTypedSnapshotDateUnicodeAndNullableMetadata() {
        var request = request();
        var out = client.verify(request, pin);
        assertThat(out.verdict()).isEqualTo(Verdict.SUPPORTED);
        var wire = server.verifyRequests.getFirst();
        assertThat(wire.path("referenceDate").isTextual()).isTrue();
        assertThat(wire.path("referenceDate").asText()).isEqualTo("2025-01-01");
        assertThat(wire.at("/questionSnapshot/referenceDate").asText()).isEqualTo("2025-01-01");
        assertThat(wire.at("/candidates/0/metadata/effectiveFrom").asText())
                .isEqualTo("2024-01-01");
        assertThat(wire.path("questionSnapshot").path("questionType").asText())
                .isEqualTo("SELECT_INCORRECT");
        assertThat(wire.at("/questionSnapshot/providedAnswerNumber").asInt()).isEqualTo(2);
        assertThat(wire.at("/candidates/0/metadata/effectiveTo").isNull()).isTrue();
        assertThat(wire.at("/candidates/0/sourceText").asText())
                .isEqualTo(request.candidates().getFirst().sourceText());
        assertThat(server.posts).containsExactly("verify");
    }

    @ParameterizedTest
    @ValueSource(strings = {"HOLD", "REJECTED"})
    void acceptsOtherCoreVerdicts(String verdict) {
        server.transform =
                n -> {
                    n.put("verdict", verdict);
                    if (verdict.equals("HOLD")) n.putArray("evidences");
                    return n;
                };
        assertThat(client.verify(request(), pin).verdict().name()).isEqualTo(verdict);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "mock", "off", "revision", "duplicate"})
    void cannotMistakeOtherModelsOrMockReadinessForVerifier(String mode) {
        server.readiness = mode;
        assertThatThrownBy(() -> client.verify(request(), pin))
                .isInstanceOf(AiClientException.class);
        assertThat(server.posts).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "unknown",
                "missing-nullable",
                "unsupported",
                "no-evidence",
                "rejected-no-evidence",
                "foreign",
                "duplicate-key",
                "duplicate-span",
                "wrong-quote",
                "utf16",
                "bad-type",
                "model",
                "prompt",
                "choices",
                "tag-version"
            })
    void rejectsInvalidOrUnsupportedResults(String mode) {
        server.transform =
                n -> {
                    var e = (ObjectNode) n.path("evidences").get(0);
                    switch (mode) {
                        case "unknown" -> n.put("confidence", 0.99);
                        case "missing-nullable" -> n.remove("choiceAnalyses");
                        case "unsupported" -> n.put("verdict", "PROBABLY");
                        case "no-evidence" -> n.putArray("evidences");
                        case "rejected-no-evidence" -> {
                            n.put("verdict", "REJECTED");
                            n.putArray("evidences");
                        }
                        case "foreign" -> e.put("chunkId", UUID.randomUUID().toString());
                        case "duplicate-key" -> {
                            var copy = e.deepCopy().put("start", 1);
                            ((com.fasterxml.jackson.databind.node.ArrayNode) n.path("evidences"))
                                    .add(copy);
                        }
                        case "duplicate-span" -> {
                            var copy = e.deepCopy().put("evidenceKey", "e2");
                            ((com.fasterxml.jackson.databind.node.ArrayNode) n.path("evidences"))
                                    .add(copy);
                        }
                        case "wrong-quote" -> e.put("quote", "변경된 문장");
                        case "utf16" -> e.put("end", e.path("quote").asText().length());
                        case "bad-type" -> e.put("start", "0");
                        case "model" -> n.put("modelRevision", "d".repeat(40));
                        case "prompt" -> n.put("promptVersion", "other-v1");
                        case "choices" -> n.putArray("choiceAnalyses");
                        case "tag-version" -> n.put("tagSetVersion", "tags-v1");
                    }
                    return n;
                };
        assertThatThrownBy(() -> client.verify(request(), pin))
                .isInstanceOf(AiClientException.class);
        assertThat(server.posts).containsExactly("verify");
    }

    @Test
    void rejectsInvalidInputBeforeAnyNetworkCall() {
        var good = request();
        assertThatThrownBy(
                        () ->
                                client.verify(
                                        new Request(
                                                good.requestId(),
                                                good.questionSnapshot(),
                                                good.referenceDate(),
                                                List.of()),
                                        pin))
                .isInstanceOf(AiClientException.class);
        assertThatThrownBy(
                        () ->
                                client.verify(
                                        new Request(
                                                good.requestId(),
                                                good.questionSnapshot(),
                                                good.referenceDate().plusDays(1),
                                                good.candidates()),
                                        pin))
                .isInstanceOf(AiClientException.class);
        assertThat(server.posts).isEmpty();
    }

    @Test
    void timeoutAndRemoteErrorsRemainTechnicalAndNeverAutoRetry() {
        server.verifyStatus = 429;
        server.retryAfter = "30";
        assertThatThrownBy(() -> client.verify(request(), pin))
                .isInstanceOfSatisfying(
                        AiClientException.class,
                        e -> {
                            assertThat(e.retryable()).isTrue();
                            assertThat(e.retryAfter()).isNotNull();
                        });
        assertThat(server.posts).hasSize(1);
        server.reset();
        server.onVerify =
                () -> {
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                };
        var shortClient = client(Duration.ofMillis(150));
        assertThatThrownBy(() -> shortClient.verify(request(), pin))
                .isInstanceOfSatisfying(
                        AiClientException.class,
                        e -> assertThat(e.kind()).isEqualTo(AiClientException.Kind.TIMEOUT));
    }

    @Test
    void configuredPinsRejectExplicitSyntheticBackends() {
        for (var name : List.of("verify:mock", "verify:off", "verify:fixture-v1", "embed:real"))
            assertThatThrownBy(
                            () -> new VerificationProperties.Pin(name, "a".repeat(40), "prompt-v1"))
                    .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new VerificationProperties(true, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
