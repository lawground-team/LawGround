package com.lawground.law.search;

import java.time.LocalDate;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class HybridSearchService {
    private final JdbcTemplate jdbc;
    private final boolean isolatedTest;

    public HybridSearchService(JdbcTemplate jdbc, Environment env) {
        this.jdbc = jdbc;
        this.isolatedTest =
                List.of(env.getActiveProfiles()).contains("test")
                        && !List.of(env.getActiveProfiles()).contains("prod");
    }

    public record Query(
            Set<UUID> lawIds,
            LocalDate referenceDate,
            String embeddingModelRevision,
            String tokenizerVersion,
            String chunkerVersion,
            float[] vector,
            List<String> tokens) {}

    public record Candidate(
            UUID chunkId,
            UUID articleId,
            UUID lawVersionId,
            String sourceText,
            double channelScore) {}

    public record Result(
            List<Candidate> dense,
            List<Candidate> lexical,
            List<ReciprocalRankFusion.Ranked> fused,
            List<UUID> lawVersionIds,
            String availability) {}

    private static final String FROM =
            """
             FROM law_chunk_indexes i JOIN law_chunks c ON c.id=i.chunk_id
             JOIN law_articles a ON a.id=c.article_id JOIN law_versions v ON v.id=a.law_version_id JOIN laws l ON l.id=v.law_id
             WHERE v.law_id=ANY(?::uuid[]) AND v.effective_from<=? AND (v.effective_to IS NULL OR v.effective_to>?)
             AND v.applicability_status='VERIFIED' AND i.embedding_model_revision=? AND i.tokenizer_version=?
             AND c.chunker_version=? AND i.source_hash=c.content_hash AND l.source_provider=ANY(?::text[])
            """;

    @Transactional(readOnly = true)
    public Result search(Query query) {
        if (query == null
                || query.lawIds() == null
                || query.lawIds().isEmpty()
                || query.lawIds().size() > 100
                || query.referenceDate() == null)
            throw new IllegalArgumentException("Explicit corpus/date required");
        SearchValues.revision(query.embeddingModelRevision());
        SearchValues.revision(query.tokenizerVersion());
        SearchValues.revision(query.chunkerVersion());
        String vector = SearchValues.vector(query.vector()),
                tokens = SearchValues.tokens(query.tokens());
        String laws =
                "{"
                        + String.join(
                                ",", query.lawIds().stream().map(UUID::toString).sorted().toList())
                        + "}";
        String providers = isolatedTest ? "{LAW_GO_KR,FIXTURE}" : "{LAW_GO_KR}";
        var versions =
                jdbc.query(
                        "SELECT v.id,v.law_id FROM law_versions v JOIN laws l ON l.id=v.law_id WHERE v.law_id=ANY(?::uuid[]) AND v.effective_from<=? AND (v.effective_to IS NULL OR v.effective_to>?) AND v.applicability_status='VERIFIED' AND l.source_provider=ANY(?::text[])",
                        (rs, n) ->
                                new UUID[] {
                                    rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)
                                },
                        laws,
                        query.referenceDate(),
                        query.referenceDate(),
                        providers);
        if (versions.stream().map(x -> x[1]).distinct().count() != query.lawIds().size())
            return new Result(
                    List.of(),
                    List.of(),
                    List.of(),
                    versions.stream().map(x -> x[0]).toList(),
                    "LAW_VERSION_UNAVAILABLE");
        if (versions.size() != query.lawIds().size())
            throw new IllegalStateException("Ambiguous verified corpus");
        var args =
                new Object[] {
                    laws,
                    query.referenceDate(),
                    query.referenceDate(),
                    query.embeddingModelRevision(),
                    query.tokenizerVersion(),
                    query.chunkerVersion(),
                    providers
                };
        var denseArgs = new ArrayList<Object>();
        denseArgs.add(vector);
        denseArgs.addAll(Arrays.asList(args));
        var dense =
                jdbc.query(
                        "SELECT c.id,a.id AS article_id,v.id AS version_id,c.source_text,(i.embedding <=> ?::vector) AS score"
                                + FROM
                                + " AND i.dense_status='READY' ORDER BY score ASC,c.id ASC LIMIT 30",
                        (rs, n) ->
                                new Candidate(
                                        rs.getObject("id", UUID.class),
                                        rs.getObject("article_id", UUID.class),
                                        rs.getObject("version_id", UUID.class),
                                        rs.getString("source_text"),
                                        rs.getDouble("score")),
                        denseArgs.toArray());
        var lexicalArgs = new ArrayList<Object>();
        lexicalArgs.add(tokens);
        lexicalArgs.addAll(Arrays.asList(args));
        lexicalArgs.add(tokens);
        var lexical =
                jdbc.query(
                        "SELECT c.id,a.id AS article_id,v.id AS version_id,c.source_text,ts_rank_cd(i.search_vector,plainto_tsquery('simple',?)) AS score"
                                + FROM
                                + " AND i.lexical_status='READY' AND i.search_vector @@ plainto_tsquery('simple',?) ORDER BY score DESC,c.id ASC LIMIT 30",
                        (rs, n) ->
                                new Candidate(
                                        rs.getObject("id", UUID.class),
                                        rs.getObject("article_id", UUID.class),
                                        rs.getObject("version_id", UUID.class),
                                        rs.getString("source_text"),
                                        rs.getDouble("score")),
                        lexicalArgs.toArray());
        String availability = dense.isEmpty() && lexical.isEmpty() ? "NO_CANDIDATES" : "READY";
        return new Result(
                dense,
                lexical,
                ReciprocalRankFusion.fuse(
                        dense.stream().map(Candidate::chunkId).toList(),
                        lexical.stream().map(Candidate::chunkId).toList()),
                versions.stream()
                        .map(x -> x[0])
                        .sorted(Comparator.comparing(UUID::toString))
                        .toList(),
                availability);
    }
}
