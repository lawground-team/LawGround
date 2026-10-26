package com.lawground.job.service;

import static com.lawground.job.service.LiveWorkerFailure.Code.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.lawground.global.error.ApiException;
import com.lawground.global.error.ErrorCode;
import com.lawground.integration.ai.config.AiProperties;
import com.lawground.integration.ai.config.VerificationProperties;
import com.lawground.job.config.LiveAnalysisProperties;
import com.lawground.law.search.LiveIndexStore;
import com.lawground.law.search.LiveRetrievalService;
import com.lawground.law.search.LiveSearchProfile;
import com.lawground.law.service.LawImportService;
import java.time.LocalDate;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Immutable admission scope. Never reads a newer question/config as a replacement for a run. */
@Service
public class LiveRunPlan {
    public static final String PIPELINE = "spring-live-retrieval-v1";
    public static final String VERIFIED_PIPELINE = "spring-live-verify-v1";
    private final LiveAnalysisProperties settings;
    private final AiProperties ai;
    private final LiveIndexStore indexes;
    private final VerificationProperties verification;

    public LiveRunPlan(
            LiveAnalysisProperties settings,
            AiProperties ai,
            LiveIndexStore indexes,
            VerificationProperties verification) {
        this.settings = settings;
        this.ai = ai;
        this.indexes = indexes;
        this.verification = verification;
    }

    public boolean enabled() {
        return settings.enabled();
    }

    public record Config(
            int schemaVersion,
            List<UUID> lawIds,
            String chunkerVersion,
            String embeddingModelRevision,
            String tokenizerVersion,
            String rerankerModelRevision,
            int candidateLimit,
            int topK,
            String corpusManifestHash,
            List<UUID> lawVersionIds) {
        public Config {
            lawIds = List.copyOf(lawIds);
            lawVersionIds = List.copyOf(lawVersionIds);
            new LiveSearchProfile(embeddingModelRevision, tokenizerVersion, chunkerVersion);
            if (schemaVersion != 1
                    || lawIds.isEmpty()
                    || lawIds.size() > 100
                    || new HashSet<>(lawIds).size() != lawIds.size()
                    || lawVersionIds.size() != lawIds.size()
                    || new HashSet<>(lawVersionIds).size() != lawVersionIds.size()
                    || rerankerModelRevision == null
                    || !rerankerModelRevision.matches("[0-9a-f]{40}")
                    || corpusManifestHash == null
                    || !corpusManifestHash.matches("[0-9a-f]{64}")
                    || candidateLimit < 1
                    || candidateLimit > 30
                    || topK < 1
                    || topK > 5
                    || topK > candidateLimit)
                throw new IllegalArgumentException("Invalid frozen live plan");
        }

        public LiveSearchProfile profile() {
            return new LiveSearchProfile(embeddingModelRevision, tokenizerVersion, chunkerVersion);
        }
    }

    public record VerifiedConfig(
            int schemaVersion, Config retrieval, VerificationProperties.Pin verification) {}

    public record Admission(
            Object config,
            Map<String, Object> versions,
            Map<String, Object> corpus,
            String pipelineVersion) {}

    public void validateConfiguration() {
        LiveSearchProfile.from(ai, LawImportService.CHUNKER);
        if (ai.rerankerModelRevision() == null
                || !ai.rerankerModelRevision().matches("[0-9a-f]{40}"))
            throw new IllegalArgumentException("Pinned reranker commit required");
    }

    public Admission admit(JsonNode snapshot) {
        validateConfiguration();
        LocalDate date = date(snapshot);
        queryText(snapshot);
        var profile = LiveSearchProfile.from(ai, LawImportService.CHUNKER);
        var corpus = indexes.inspect(settings.lawIds(), date, profile);
        if (corpus.availability() != LiveIndexStore.Availability.READY)
            throw new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    ErrorCode.REQUEST_FAILED,
                    "지정한 기준일 법령과 전체 색인이 준비되지 않았습니다.");
        var config =
                new Config(
                        1,
                        settings.lawIds().stream()
                                .sorted(Comparator.comparing(UUID::toString))
                                .toList(),
                        profile.chunker(),
                        profile.model(),
                        profile.tokenizer(),
                        ai.rerankerModelRevision(),
                        settings.candidateLimit(),
                        settings.topK(),
                        corpus.manifestHash(),
                        corpus.versionIds());
        var versions = new LinkedHashMap<String, Object>();
        versions.put("pipelineVersion", verification.enabled() ? VERIFIED_PIPELINE : PIPELINE);
        versions.put("embeddingModelRevision", profile.model());
        versions.put("tokenizerVersion", profile.tokenizer());
        // Public Versions has six fixed fields. Reranker pin remains in config and checkpoint.
        versions.put("promptVersion", verification.enabled() ? verification.promptVersion() : null);
        versions.put(
                "verifierModelRevision",
                verification.enabled() ? verification.modelRevision() : null);
        versions.put("corpusManifestHash", corpus.manifestHash());
        return new Admission(
                verification.enabled() ? new VerifiedConfig(2, config, verification.pin()) : config,
                versions,
                Map.of("hash", corpus.manifestHash(), "lawVersionIds", corpus.versionIds()),
                verification.enabled() ? VERIFIED_PIPELINE : PIPELINE);
    }

    public VerifiedConfig readVerified(JsonNode value) {
        try {
            if (!value.isObject()
                    || value.size() != 3
                    || !value.path("schemaVersion").isInt()
                    || value.path("schemaVersion").intValue() != 2)
                throw new IllegalArgumentException();
            var node = value.path("verification");
            if (!node.isObject()
                    || node.size() != 3
                    || !node.path("modelName").isTextual()
                    || !node.path("modelRevision").isTextual()
                    || !node.path("promptVersion").isTextual())
                throw new IllegalArgumentException();
            var pin =
                    new VerificationProperties.Pin(
                            node.path("modelName").textValue(),
                            node.path("modelRevision").textValue(),
                            node.path("promptVersion").textValue());
            if (!verification.enabled()) throw new LiveWorkerFailure(VERIFIER_NOT_CONFIGURED);
            var current = verification.pin();
            if (!pin.modelName().equals(current.modelName())
                    || !pin.modelRevision().equals(current.modelRevision()))
                throw new LiveWorkerFailure(MODEL_REVISION_MISMATCH);
            if (!pin.promptVersion().equals(current.promptVersion()))
                throw new LiveWorkerFailure(PROMPT_VERSION_MISMATCH);
            return new VerifiedConfig(2, read(value.path("retrieval")), pin);
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new LiveWorkerFailure(CHECKPOINT_INVALID);
        }
    }

    public Config read(JsonNode value) {
        try {
            var fields =
                    Set.of(
                            "schemaVersion",
                            "lawIds",
                            "chunkerVersion",
                            "embeddingModelRevision",
                            "tokenizerVersion",
                            "rerankerModelRevision",
                            "candidateLimit",
                            "topK",
                            "corpusManifestHash",
                            "lawVersionIds");
            if (!value.isObject() || value.size() != fields.size())
                throw new IllegalArgumentException();
            for (String name : fields) {
                JsonNode node = value.path(name);
                if (List.of("schemaVersion", "candidateLimit", "topK").contains(name)) {
                    if (!node.isIntegralNumber() || !node.canConvertToInt())
                        throw new IllegalArgumentException();
                } else if (List.of("lawIds", "lawVersionIds").contains(name)) {
                    if (!node.isArray() || node.isEmpty() || node.size() > 100)
                        throw new IllegalArgumentException();
                } else if (!node.isTextual()) throw new IllegalArgumentException();
            }
            return new Config(
                    value.path("schemaVersion").intValue(),
                    uuids(value.path("lawIds")),
                    value.path("chunkerVersion").textValue(),
                    value.path("embeddingModelRevision").textValue(),
                    value.path("tokenizerVersion").textValue(),
                    value.path("rerankerModelRevision").textValue(),
                    value.path("candidateLimit").intValue(),
                    value.path("topK").intValue(),
                    value.path("corpusManifestHash").textValue(),
                    uuids(value.path("lawVersionIds")));
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new LiveWorkerFailure(CHECKPOINT_INVALID);
        }
    }

    public LiveRetrievalService.Query query(Config config, JsonNode snapshot) {
        if (!Objects.equals(config.embeddingModelRevision(), ai.embeddingModelRevision())
                || !Objects.equals(config.tokenizerVersion(), ai.tokenizerVersion())
                || !Objects.equals(config.rerankerModelRevision(), ai.rerankerModelRevision()))
            throw new LiveWorkerFailure(MODEL_REVISION_MISMATCH);
        return new LiveRetrievalService.Query(
                Set.copyOf(config.lawIds()),
                date(snapshot),
                queryText(snapshot),
                config.chunkerVersion(),
                config.candidateLimit(),
                config.topK());
    }

    public void requireCorpus(Config config, JsonNode snapshot) {
        indexes.requireUnchanged(
                Set.copyOf(config.lawIds()),
                date(snapshot),
                config.profile(),
                config.corpusManifestHash());
    }

    public static String queryText(JsonNode snapshot) {
        try {
            if (!snapshot.path("revision").isIntegralNumber()
                    || snapshot.path("revision").asLong() < 1
                    || !List.of("BROKER_LAW").contains(text(snapshot, "subject"))
                    || !List.of("SELECT_CORRECT", "SELECT_INCORRECT")
                            .contains(text(snapshot, "questionType"))
                    || !snapshot.path("providedAnswerNumber").isIntegralNumber()
                    || snapshot.path("providedAnswerNumber").asInt() < 1
                    || snapshot.path("providedAnswerNumber").asInt() > 5)
                throw new IllegalArgumentException();
            var choices = snapshot.path("choices");
            if (!choices.isArray() || choices.size() != 5) throw new IllegalArgumentException();
            var ordered = new TreeMap<Integer, String>();
            for (var choice : choices) {
                var number = choice.path("number");
                if (!number.isIntegralNumber()
                        || number.intValue() < 1
                        || number.intValue() > 5
                        || ordered.put(number.intValue(), text(choice, "text")) != null)
                    throw new IllegalArgumentException();
            }
            var value =
                    new StringBuilder("subject: ")
                            .append(text(snapshot, "subject"))
                            .append("\nquestionType: ")
                            .append(text(snapshot, "questionType"))
                            .append("\nquestion: ")
                            .append(text(snapshot, "stem"));
            ordered.forEach(
                    (number, choice) ->
                            value.append("\nchoice[").append(number).append("]: ").append(choice));
            value.append("\nprovidedAnswerNumber: ")
                    .append(snapshot.path("providedAnswerNumber").intValue());
            if (value.codePointCount(0, value.length()) > 30000)
                throw new IllegalArgumentException();
            return value.toString();
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new LiveWorkerFailure(SNAPSHOT_INVALID);
        }
    }

    private static String text(JsonNode object, String name) {
        var value = object.path(name);
        if (!value.isTextual()
                || value.textValue().isBlank()
                || value.textValue().indexOf('\0') >= 0) throw new IllegalArgumentException();
        return value.textValue();
    }

    private static LocalDate date(JsonNode snapshot) {
        try {
            return LocalDate.parse(text(snapshot, "referenceDate"));
        } catch (IllegalArgumentException | java.time.DateTimeException bad) {
            throw new LiveWorkerFailure(SNAPSHOT_INVALID);
        }
    }

    private static List<UUID> uuids(JsonNode values) {
        var output = new ArrayList<UUID>();
        for (var value : values) {
            if (!value.isTextual()) throw new IllegalArgumentException();
            UUID id = UUID.fromString(value.textValue());
            if (!id.toString().equals(value.textValue())) throw new IllegalArgumentException();
            output.add(id);
        }
        return output;
    }
}
