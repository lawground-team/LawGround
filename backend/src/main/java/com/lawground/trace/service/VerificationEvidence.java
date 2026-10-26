package com.lawground.trace.service;

import com.fasterxml.jackson.databind.*;
import com.lawground.global.config.JsonData;
import com.lawground.integration.ai.client.VerificationWire;
import com.lawground.integration.ai.config.VerificationProperties.Pin;
import com.lawground.integration.ai.dto.VerifyDtos.*;
import com.lawground.job.service.LiveRunPlan.Config;
import com.lawground.job.service.LiveWorkerFailure;
import com.lawground.job.service.LiveWorkerFailure.Code;
import com.lawground.law.search.LiveIndexStore;
import com.lawground.law.search.LiveRetrievalService.Hit;
import com.lawground.trace.dto.TraceDtos;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Source metadata always comes from Spring's DB; no network calls in this class. */
@Service
public class VerificationEvidence {
    private final EvidenceValidator evidence;
    private final LiveIndexStore indexes;
    private final ObjectMapper mapper, canonical;

    public VerificationEvidence(
            EvidenceValidator evidence, LiveIndexStore indexes, ObjectMapper mapper) {
        this.evidence = evidence;
        this.indexes = indexes;
        this.mapper =
                mapper.copy()
                        .enable(
                                DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                                DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,
                                DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
        this.canonical =
                this.mapper
                        .copy()
                        .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
                        .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Request prepare(UUID requestId, JsonNode snapshot, Config config, List<Hit> hits) {
        var request = build(requestId, snapshot, config, hits);
        indexes.requireUnchanged(
                Set.copyOf(config.lawIds()),
                request.referenceDate(),
                config.profile(),
                config.corpusManifestHash());
        return request;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Request prepareLocked(UUID requestId, JsonNode snapshot, Config config, List<Hit> hits) {
        Snapshot q = snapshot(snapshot);
        indexes.lockUnchanged(
                Set.copyOf(config.lawIds()),
                q.referenceDate(),
                config.profile(),
                config.corpusManifestHash());
        return build(requestId, snapshot, config, hits);
    }

    private Request build(UUID requestId, JsonNode snapshot, Config config, List<Hit> hits) {
        var q = snapshot(snapshot);
        if (hits == null || hits.isEmpty() || hits.size() > 5)
            throw new LiveWorkerFailure(Code.VERIFICATION_CANDIDATES_UNAVAILABLE);
        var ids = new HashSet<UUID>();
        var quotes = new ArrayList<EvidenceValidator.Quote>();
        for (var hit : hits) {
            if (hit == null
                    || hit.chunkId() == null
                    || hit.sourceText() == null
                    || !ids.add(hit.chunkId()))
                throw new LiveWorkerFailure(Code.CHECKPOINT_INVALID);
            quotes.add(
                    new EvidenceValidator.Quote(
                            hit.chunkId(),
                            hit.sourceText(),
                            0,
                            hit.sourceText().codePointCount(0, hit.sourceText().length())));
        }
        List<TraceDtos.Evidence> sources;
        try {
            sources =
                    evidence.resolve(
                            ids, Set.copyOf(config.lawVersionIds()), q.referenceDate(), quotes);
        } catch (IllegalArgumentException invalid) {
            throw new LiveWorkerFailure(Code.CHECKPOINT_INVALID);
        }
        var candidates = new ArrayList<Candidate>();
        for (int i = 0; i < hits.size(); i++) {
            var hit = hits.get(i);
            var source = sources.get(i);
            if (!source.articleId().equals(hit.articleId())
                    || !source.lawVersionId().equals(hit.lawVersionId()))
                throw new LiveWorkerFailure(Code.CHECKPOINT_INVALID);
            candidates.add(
                    new Candidate(
                            hit.chunkId(),
                            hit.sourceText(),
                            new Metadata(
                                    source.articleId(),
                                    source.lawVersionId(),
                                    source.lawTitle(),
                                    source.articleLabel(),
                                    source.effectiveFrom(),
                                    source.effectiveTo())));
        }
        var request = new Request(requestId, q, q.referenceDate(), List.copyOf(candidates));
        VerificationWire.request(request);
        return request;
    }

    private Snapshot snapshot(JsonNode node) {
        try {
            return mapper.treeToValue(node, Snapshot.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            throw new LiveWorkerFailure(Code.SNAPSHOT_INVALID);
        }
    }

    private record Content(
            Snapshot questionSnapshot,
            java.time.LocalDate referenceDate,
            List<Candidate> candidates) {}

    public String fingerprint(Request request) {
        try {
            return JsonData.hash(
                    canonical.writeValueAsString(
                            new Content(
                                    request.questionSnapshot(),
                                    request.referenceDate(),
                                    request.candidates())));
        } catch (com.fasterxml.jackson.core.JsonProcessingException bad) {
            throw new LiveWorkerFailure(Code.CHECKPOINT_INVALID);
        }
    }

    public Map<String, Object> checkpoint(Response response, Request request, Pin pin) {
        VerificationWire.response(response, request, pin);
        return Map.of(
                "schemaVersion",
                1,
                "verificationPerformed",
                true,
                "requestHash",
                fingerprint(request),
                "result",
                response);
    }

    public Response fromCheckpoint(JsonNode node, Request request, Pin pin) {
        if (node == null
                || !node.isObject()
                || node.size() != 4
                || !node.path("schemaVersion").isInt()
                || node.path("schemaVersion").intValue() != 1
                || !node.path("verificationPerformed").isBoolean()
                || !node.path("verificationPerformed").booleanValue()
                || !node.path("requestHash").isTextual()
                || !fingerprint(request).equals(node.path("requestHash").textValue()))
            throw new LiveWorkerFailure(Code.CHECKPOINT_INVALID);
        try {
            var response = mapper.treeToValue(node.path("result"), Response.class);
            VerificationWire.response(response, request, pin);
            return response;
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            throw new LiveWorkerFailure(Code.CHECKPOINT_INVALID);
        }
    }

    public List<TraceDtos.Evidence> resolve(Response response, Request request, Config config) {
        return evidence.resolve(
                request.candidates().stream()
                        .map(Candidate::chunkId)
                        .collect(java.util.stream.Collectors.toSet()),
                Set.copyOf(config.lawVersionIds()),
                request.referenceDate(),
                response.evidences().stream()
                        .map(
                                e ->
                                        new EvidenceValidator.Quote(
                                                e.chunkId(), e.quote(), e.start(), e.end()))
                        .toList());
    }
}
