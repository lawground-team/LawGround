package com.lawground.law.search;

import com.lawground.integration.ai.client.AiInternalClient;
import com.lawground.integration.ai.config.AiProperties;
import com.lawground.integration.ai.dto.AiDtos.*;
import java.time.LocalDate;
import java.util.*;
import org.springframework.stereotype.Service;

/** Retrieval only. Does not submit jobs, persist legal verdicts or implement /verify. */
@Service
public class LiveRetrievalService {
    private final LiveIndexStore store;
    private final AiInternalClient client;
    private final AiProperties properties;

    public LiveRetrievalService(
            LiveIndexStore store, AiInternalClient client, AiProperties properties) {
        this.store = store;
        this.client = client;
        this.properties = properties;
    }

    public record Query(
            Set<UUID> lawIds,
            LocalDate referenceDate,
            String text,
            String chunkerVersion,
            int rerankCandidateLimit,
            int topK) {
        public Query {
            if (lawIds == null
                    || lawIds.isEmpty()
                    || lawIds.size() > 100
                    || lawIds.stream().anyMatch(Objects::isNull)
                    || referenceDate == null
                    || text == null
                    || text.isBlank()
                    || text.indexOf('\0') >= 0
                    || text.codePointCount(0, text.length()) > 30000
                    || rerankCandidateLimit < 1
                    || rerankCandidateLimit > 30
                    || topK < 1
                    || topK > 5
                    || topK > rerankCandidateLimit)
                throw new IllegalArgumentException(
                        "Explicit corpus/date/query and bounded rerank limits required");
            lawIds = Set.copyOf(lawIds);
        }

        @Override
        public String toString() {
            return "RetrievalQuery[content omitted]";
        }
    }

    public enum Status {
        RETRIEVED,
        NO_CANDIDATES,
        LAW_VERSION_UNAVAILABLE,
        INDEX_NOT_READY
    }

    public record Hit(
            UUID chunkId,
            UUID articleId,
            UUID lawVersionId,
            String sourceText,
            double rrfScore,
            double rerankScore) {
        @Override
        public String toString() {
            return "RetrievalHit[content omitted]";
        }
    }

    public record Result(
            Status status,
            String manifestHash,
            List<UUID> lawVersionIds,
            LiveSearchProfile profile,
            String rerankerRevision,
            int fusedCandidateCount,
            int rerankedCandidateCount,
            List<Hit> hits) {
        public boolean verificationPerformed() {
            return false;
        }
    }

    public Result search(Query query) {
        LiveSearchProfile.outsideTransaction();
        Objects.requireNonNull(query, "Query required");
        var profile = LiveSearchProfile.from(properties, query.chunkerVersion());
        var corpus = store.inspect(query.lawIds(), query.referenceDate(), profile);
        if (corpus.availability() != LiveIndexStore.Availability.READY)
            return new Result(
                    Status.valueOf(corpus.availability().name()),
                    corpus.manifestHash(),
                    corpus.versionIds(),
                    profile,
                    null,
                    0,
                    0,
                    List.of());
        UUID id = UUID.randomUUID();
        var input = List.of(new IdText(id, query.text()));
        var tokens = client.tokenize(UUID.randomUUID(), input).items().getFirst().tokens();
        SearchValues.tokens(tokens);
        float[] vector =
                LiveSearchProfile.vector(
                        client.embed(UUID.randomUUID(), input).items().getFirst().vector());
        var phase =
                store.search(
                        query.lawIds(),
                        query.referenceDate(),
                        profile,
                        corpus.manifestHash(),
                        vector,
                        tokens);
        var result = phase.result();
        if (result.fused().isEmpty())
            return new Result(
                    Status.NO_CANDIDATES,
                    corpus.manifestHash(),
                    corpus.versionIds(),
                    profile,
                    null,
                    0,
                    0,
                    List.of());
        var candidates = new HashMap<UUID, HybridSearchService.Candidate>();
        result.dense().forEach(c -> candidates.put(c.chunkId(), c));
        result.lexical().forEach(c -> candidates.put(c.chunkId(), c));
        var fused = result.fused().stream().limit(query.rerankCandidateLimit()).toList();
        var scores = new HashMap<UUID, Double>();
        var request = new ArrayList<RerankCandidate>();
        for (var candidate : fused) {
            var original = candidates.get(candidate.chunkId());
            if (original == null) throw new LiveSearchFailure(LiveSearchFailure.Code.INVALID_INDEX);
            scores.put(candidate.chunkId(), candidate.score());
            request.add(new RerankCandidate(candidate.chunkId(), original.sourceText()));
        }
        var ranked =
                client.rerank(
                        UUID.randomUUID(),
                        query.text(),
                        request,
                        Math.min(query.topK(), request.size()));
        store.requireUnchanged(
                query.lawIds(), query.referenceDate(), profile, corpus.manifestHash());
        var hits =
                ranked.ranked().stream()
                        .map(
                                item -> {
                                    var source = candidates.get(item.chunkId());
                                    return new Hit(
                                            item.chunkId(),
                                            source.articleId(),
                                            source.lawVersionId(),
                                            source.sourceText(),
                                            scores.get(item.chunkId()),
                                            item.score());
                                })
                        .toList();
        return new Result(
                Status.RETRIEVED,
                corpus.manifestHash(),
                corpus.versionIds(),
                profile,
                ranked.modelRevision(),
                result.fused().size(),
                request.size(),
                hits);
    }
}
