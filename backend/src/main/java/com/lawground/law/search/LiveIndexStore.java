package com.lawground.law.search;

import static com.lawground.law.search.LiveSearchFailure.Code.*;

import com.lawground.global.config.JsonData;
import com.lawground.law.service.SourceText;
import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Short DB transactions only. No model/network invocation belongs in this store. */
@Service
public class LiveIndexStore {
    private final JdbcTemplate jdbc;
    private final HybridSearchService hybrid;

    public LiveIndexStore(JdbcTemplate jdbc, HybridSearchService hybrid) {
        this.jdbc = jdbc;
        this.hybrid = hybrid;
    }

    record Source(
            UUID chunkId,
            UUID lawId,
            UUID versionId,
            UUID articleId,
            String versionHash,
            String articleHash,
            String chunker,
            String hash,
            int ordinal,
            int start,
            int end,
            String sourceText,
            String searchText,
            String articleText,
            String sourceUrl) {
        @Override
        public String toString() {
            return "ReviewedChunk[content omitted]";
        }
    }

    public enum Availability {
        READY,
        LAW_VERSION_UNAVAILABLE,
        INDEX_NOT_READY
    }

    public record Corpus(Availability availability, String manifestHash, List<UUID> versionIds) {}

    record Search(Corpus corpus, HybridSearchService.Result result) {}

    private static final String SOURCE =
            """
        SELECT c.id AS chunk_id,l.id AS law_id,v.id AS version_id,a.id AS article_id,
            v.content_hash AS version_hash,a.content_hash AS article_hash,c.chunker_version,
            c.content_hash,c.ordinal,c.article_start,c.article_end,c.source_text,c.search_text,
            a.source_text AS article_text,v.source_url
        FROM law_chunks c JOIN law_articles a ON a.id=c.article_id
        JOIN law_versions v ON v.id=a.law_version_id JOIN laws l ON l.id=v.law_id
        """;
    private static final String REVIEWED =
            "l.source_provider='LAW_GO_KR' AND v.applicability_status='VERIFIED'";
    private static final String READY =
            """
        COALESCE(i.dense_status='READY' AND i.lexical_status='READY'
          AND i.embedding IS NOT NULL AND abs(vector_norm(i.embedding)-1)<0.001
          AND i.tokens IS NOT NULL AND i.search_vector IS NOT NULL
          AND i.search_vector=to_tsvector('simple',array_to_string(i.tokens,' ')),false)
        """;

    @Transactional(readOnly = true)
    public Source load(UUID chunkId, LiveSearchProfile profile) {
        var sources =
                jdbc.query(
                        SOURCE + " WHERE c.id=? AND " + REVIEWED, (rs, n) -> source(rs), chunkId);
        if (sources.size() != 1) throw new LiveSearchFailure(INVALID_SOURCE);
        validate(sources.getFirst(), profile);
        return sources.getFirst();
    }

    @Transactional(readOnly = true)
    public boolean ready(Source source, LiveSearchProfile profile) {
        var rows =
                jdbc.query(
                        SOURCE.replace(
                                        "SELECT c.id",
                                        "SELECT "
                                                + READY
                                                + " AS ready,i.dense_status,i.lexical_status,c.id")
                                + " LEFT JOIN law_chunk_indexes i ON i.chunk_id=c.id AND i.embedding_model_revision=? AND i.tokenizer_version=? AND i.source_hash=c.content_hash"
                                + " WHERE c.id=? AND "
                                + REVIEWED,
                        (rs, n) -> {
                            if (!source(rs).equals(source))
                                throw new LiveSearchFailure(SOURCE_CHANGED);
                            boolean ready = rs.getBoolean("ready");
                            if (!ready
                                    && "READY".equals(rs.getString("dense_status"))
                                    && "READY".equals(rs.getString("lexical_status")))
                                throw new LiveSearchFailure(INVALID_INDEX);
                            return ready;
                        },
                        profile.model(),
                        profile.tokenizer(),
                        source.chunkId());
        if (rows.isEmpty()) throw new LiveSearchFailure(SOURCE_CHANGED);
        return rows.getFirst();
    }

    @Transactional
    public boolean publish(
            Source expected, LiveSearchProfile profile, float[] vector, List<String> tokens) {
        String encoded = SearchValues.vector(vector), joined = SearchValues.tokens(tokens);
        // Hold source rows only during revalidation + atomic publish, never during inference.
        var current =
                jdbc.query(
                        SOURCE + " WHERE c.id=? AND " + REVIEWED + " FOR SHARE OF l,v,a,c",
                        (rs, n) -> source(rs),
                        expected.chunkId());
        if (current.size() != 1 || !current.getFirst().equals(expected))
            throw new LiveSearchFailure(SOURCE_CHANGED);
        validate(current.getFirst(), profile);
        if (ready(expected, profile)) return false;
        return jdbc.update(
                        connection -> {
                            var statement =
                                    connection.prepareStatement(
                                            """
                INSERT INTO law_chunk_indexes(id,chunk_id,embedding_model_revision,tokenizer_version,source_hash,
                    embedding,dense_status,tokens,search_vector,lexical_status)
                VALUES (?,?,?,?,?,?::vector,'READY',?,to_tsvector('simple',?),'READY')
                ON CONFLICT(chunk_id,embedding_model_revision,tokenizer_version,source_hash)
                DO UPDATE SET embedding=EXCLUDED.embedding,tokens=EXCLUDED.tokens,search_vector=EXCLUDED.search_vector,
                    dense_status='READY',lexical_status='READY',error_code=NULL,updated_at=now()
                WHERE law_chunk_indexes.dense_status<>'READY' OR law_chunk_indexes.lexical_status<>'READY'
                """);
                            statement.setObject(1, UUID.randomUUID());
                            statement.setObject(2, expected.chunkId());
                            statement.setString(3, profile.model());
                            statement.setString(4, profile.tokenizer());
                            statement.setString(5, expected.hash());
                            statement.setString(6, encoded);
                            statement.setArray(
                                    7,
                                    connection.createArrayOf(
                                            "text", tokens.toArray(String[]::new)));
                            statement.setString(8, joined);
                            return statement;
                        })
                == 1;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Corpus inspect(Set<UUID> lawIds, LocalDate date, LiveSearchProfile profile) {
        String ids = ids(lawIds);
        var versions =
                jdbc.query(
                        """
            SELECT v.id,v.law_id,v.content_hash,v.effective_from,v.effective_to,v.source_url
            FROM law_versions v JOIN laws l ON l.id=v.law_id
            WHERE v.law_id=ANY(?::uuid[]) AND v.effective_from<=? AND (v.effective_to IS NULL OR v.effective_to>?)
                AND l.source_provider='LAW_GO_KR' AND v.applicability_status='VERIFIED' ORDER BY v.id
            """,
                        (rs, n) ->
                                new Version(
                                        rs.getObject("id", UUID.class),
                                        rs.getObject("law_id", UUID.class),
                                        rs.getString("content_hash")
                                                + ":"
                                                + rs.getString("effective_from")
                                                + ":"
                                                + rs.getString("effective_to")
                                                + ":"
                                                + rs.getString("source_url")),
                        ids,
                        date,
                        date);
        if (versions.stream().map(Version::law).distinct().count() != lawIds.size())
            return new Corpus(
                    Availability.LAW_VERSION_UNAVAILABLE,
                    null,
                    versions.stream().map(Version::id).toList());
        if (versions.size() != lawIds.size()) throw new LiveSearchFailure(INVALID_SOURCE);
        var versionIds = versions.stream().map(Version::id).toList();
        String selected = ids(new HashSet<>(versionIds));
        int articleCount =
                jdbc.queryForObject(
                        "SELECT count(*) FROM law_articles WHERE law_version_id=ANY(?::uuid[])",
                        Integer.class,
                        selected);
        String sql =
                SOURCE.replace(
                                "SELECT c.id",
                                "SELECT "
                                        + READY
                                        + " AS index_ready,i.id AS index_id,i.updated_at::text AS index_updated,c.id")
                        + " LEFT JOIN law_chunk_indexes i ON i.chunk_id=c.id AND i.embedding_model_revision=? AND i.tokenizer_version=? AND i.source_hash=c.content_hash"
                        + " WHERE v.id=ANY(?::uuid[]) AND c.chunker_version=? AND "
                        + REVIEWED
                        + " ORDER BY v.id,a.id,c.ordinal";
        var digest =
                new StringBuilder(profile.model())
                        .append(':')
                        .append(profile.tokenizer())
                        .append(':')
                        .append(profile.chunker());
        for (var version : versions)
            digest.append('\n').append(version.id()).append(':').append(version.fingerprint());
        var articles = new HashSet<UUID>();
        boolean[] ready = {articleCount > 0};
        Source[] previous = {null};
        jdbc.query(
                sql,
                rs -> {
                    Source source = source(rs);
                    validate(source, profile);
                    Source prior = previous[0];
                    boolean sameArticle =
                            prior != null && source.articleId().equals(prior.articleId());
                    if ((!sameArticle && (source.start() != 0 || source.ordinal() != 0))
                            || (sameArticle
                                    && (source.start() != prior.end()
                                            || source.ordinal() != prior.ordinal() + 1)))
                        throw new LiveSearchFailure(INVALID_SOURCE);
                    if (!sameArticle && prior != null) complete(prior);
                    previous[0] = source;
                    articles.add(source.articleId());
                    ready[0] &= rs.getBoolean("index_ready");
                    digest.append('\n')
                            .append(rs.getString("index_id"))
                            .append(':')
                            .append(rs.getString("index_updated"))
                            .append(':')
                            .append(source.chunkId())
                            .append(':')
                            .append(source.articleId())
                            .append(':')
                            .append(source.articleHash())
                            .append(':')
                            .append(source.hash())
                            .append(':')
                            .append(source.start())
                            .append(':')
                            .append(source.end())
                            .append(':')
                            .append(JsonData.hash(source.sourceText()));
                },
                profile.model(),
                profile.tokenizer(),
                selected,
                profile.chunker());
        if (previous[0] != null) complete(previous[0]);
        ready[0] &= articles.size() == articleCount;
        return new Corpus(
                ready[0] ? Availability.READY : Availability.INDEX_NOT_READY,
                JsonData.hash(digest.toString()),
                versionIds);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Search search(
            Set<UUID> lawIds,
            LocalDate date,
            LiveSearchProfile profile,
            String manifest,
            float[] vector,
            List<String> tokens) {
        Corpus corpus = inspect(lawIds, date, profile);
        unchanged(corpus, manifest);
        var result =
                hybrid.search(
                        new HybridSearchService.Query(
                                lawIds,
                                date,
                                profile.model(),
                                profile.tokenizer(),
                                profile.chunker(),
                                vector,
                                tokens));
        if (!result.lawVersionIds().equals(corpus.versionIds()))
            throw new LiveSearchFailure(CORPUS_CHANGED);
        return new Search(corpus, result);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public void requireUnchanged(
            Set<UUID> laws, LocalDate date, LiveSearchProfile profile, String manifest) {
        unchanged(inspect(laws, date, profile), manifest);
    }

    /** Hold source/index rows only for checkpoint/final DB commits, never for model calls. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void lockUnchanged(
            Set<UUID> laws, java.time.LocalDate date, LiveSearchProfile profile, String manifest) {
        String selected = ids(laws);
        // Import serializes through the law row. Lock laws first, in deterministic order.
        jdbc.queryForList(
                "SELECT id FROM laws WHERE id=ANY(?::uuid[]) ORDER BY id FOR SHARE", selected);
        jdbc.queryForList(
                "SELECT v.id FROM law_versions v WHERE v.law_id=ANY(?::uuid[]) AND v.effective_from<=? AND (v.effective_to IS NULL OR v.effective_to>?) ORDER BY v.id FOR SHARE",
                selected,
                date,
                date);
        jdbc.queryForList(
                "SELECT a.id FROM law_articles a JOIN law_versions v ON v.id=a.law_version_id WHERE v.law_id=ANY(?::uuid[]) AND v.effective_from<=? AND (v.effective_to IS NULL OR v.effective_to>?) ORDER BY a.id FOR SHARE OF a",
                selected,
                date,
                date);
        jdbc.queryForList(
                "SELECT c.id FROM law_chunks c JOIN law_articles a ON a.id=c.article_id JOIN law_versions v ON v.id=a.law_version_id WHERE v.law_id=ANY(?::uuid[]) AND v.effective_from<=? AND (v.effective_to IS NULL OR v.effective_to>?) ORDER BY c.id FOR SHARE OF c",
                selected,
                date,
                date);
        jdbc.queryForList(
                "SELECT i.id FROM law_chunk_indexes i JOIN law_chunks c ON c.id=i.chunk_id JOIN law_articles a ON a.id=c.article_id JOIN law_versions v ON v.id=a.law_version_id WHERE v.law_id=ANY(?::uuid[]) AND v.effective_from<=? AND (v.effective_to IS NULL OR v.effective_to>?) AND i.embedding_model_revision=? AND i.tokenizer_version=? ORDER BY i.id FOR SHARE OF i",
                selected,
                date,
                date,
                profile.model(),
                profile.tokenizer());
        unchanged(inspect(laws, date, profile), manifest);
    }

    private static void unchanged(Corpus corpus, String manifest) {
        if (corpus.availability() != Availability.READY
                || !Objects.equals(manifest, corpus.manifestHash()))
            throw new LiveSearchFailure(CORPUS_CHANGED);
    }

    private record Version(UUID id, UUID law, String fingerprint) {}

    private static void complete(Source source) {
        if (source.end() != source.articleText().codePointCount(0, source.articleText().length()))
            throw new LiveSearchFailure(INVALID_SOURCE);
    }

    private static String ids(Set<UUID> values) {
        if (values == null
                || values.isEmpty()
                || values.size() > 100
                || values.stream().anyMatch(Objects::isNull))
            throw new IllegalArgumentException("Explicit corpus of 1..100 laws required");
        return "{" + String.join(",", values.stream().map(UUID::toString).sorted().toList()) + "}";
    }

    private static Source source(ResultSet rs) throws SQLException {
        return new Source(
                rs.getObject("chunk_id", UUID.class),
                rs.getObject("law_id", UUID.class),
                rs.getObject("version_id", UUID.class),
                rs.getObject("article_id", UUID.class),
                rs.getString("version_hash"),
                rs.getString("article_hash"),
                rs.getString("chunker_version"),
                rs.getString("content_hash"),
                rs.getInt("ordinal"),
                rs.getInt("article_start"),
                rs.getInt("article_end"),
                rs.getString("source_text"),
                rs.getString("search_text"),
                rs.getString("article_text"),
                rs.getString("source_url"));
    }

    private static void validate(Source source, LiveSearchProfile profile) {
        try {
            URI url = URI.create(source.sourceUrl());
            if (!profile.chunker().equals(source.chunker())
                    || !JsonData.hash(source.searchText()).equals(source.hash())
                    || !JsonData.hash(source.articleText()).equals(source.articleHash())
                    || !"https".equals(url.getScheme())
                    || url.getUserInfo() != null
                    || !List.of("law.go.kr", "www.law.go.kr").contains(url.getHost()))
                throw new IllegalArgumentException();
            SourceText.requireQuote(
                    source.articleText(), source.start(), source.end(), source.sourceText());
            for (String text : List.of(source.searchText(), source.sourceText()))
                if (text.isBlank()
                        || text.codePointCount(0, text.length()) > 30000
                        || text.indexOf('\0') >= 0) throw new IllegalArgumentException();
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new LiveSearchFailure(INVALID_SOURCE);
        }
    }
}
