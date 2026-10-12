package com.lawground.law.service;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.lawground.global.config.JsonData;
import com.lawground.law.dto.LawImport;
import java.net.URI;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LawImportService {
    public static final String CHUNKER = "article-span-v1";
    private final JdbcTemplate jdbc;
    private final ObjectMapper canonical;
    private final Clock clock;
    private final Environment environment;

    public LawImportService(
            JdbcTemplate jdbc, ObjectMapper mapper, Clock clock, Environment environment) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.environment = environment;
        this.canonical =
                mapper.copy()
                        .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
                        .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    public record Imported(
            UUID versionId, List<UUID> articleIds, List<UUID> chunkIds, boolean reused) {}

    public static class SourceConflict extends RuntimeException {
        public SourceConflict() {
            super("SOURCE_CONFLICT: immutable source version differs");
        }
    }

    @Transactional
    public Imported importVersion(LawImport input) {
        validate(input);
        String hash = JsonData.hash(write(input));
        // Serialize initial creation too; a missing laws row cannot be row-locked yet.
        jdbc.queryForObject(
                "SELECT pg_advisory_xact_lock(hashtextextended(?,0))",
                Object.class,
                input.sourceProvider() + ":" + input.sourceLawId());
        jdbc.update(
                "INSERT INTO laws(id,source_provider,source_law_id,title) VALUES (?,?,?,?) ON CONFLICT(source_provider,source_law_id) DO NOTHING",
                UUID.randomUUID(),
                input.sourceProvider(),
                input.sourceLawId(),
                input.title());
        UUID law =
                jdbc.queryForObject(
                        "SELECT id FROM laws WHERE source_provider=? AND source_law_id=? FOR UPDATE",
                        UUID.class,
                        input.sourceProvider(),
                        input.sourceLawId());
        var old =
                jdbc.query(
                        "SELECT id,content_hash FROM law_versions WHERE law_id=? AND source_version_key=?",
                        (rs, n) -> new Existing(rs.getObject(1, UUID.class), rs.getString(2)),
                        law,
                        input.sourceVersionKey());
        if (!old.isEmpty()) {
            if (!old.get(0).hash().equals(hash)) throw new SourceConflict();
            return loaded(old.get(0).id(), true);
        }
        if (input.applicabilityStatus().equals("VERIFIED")
                && Boolean.TRUE.equals(
                        jdbc.queryForObject(
                                """
                SELECT EXISTS(SELECT 1 FROM law_versions WHERE law_id=? AND applicability_status='VERIFIED'
                  AND effective_from<COALESCE(?::date,'infinity'::date)
                  AND COALESCE(effective_to,'infinity'::date)>?::date)
                """,
                                Boolean.class,
                                law,
                                input.effectiveTo(),
                                input.effectiveFrom()))) throw new SourceConflict();
        UUID version = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO law_versions(id,law_id,source_version_key,effective_from,effective_to,applicability_status,source_url,raw_payload,content_hash,fetched_at)
                VALUES (?,?,?,?,?,?,?,?::jsonb,?,?)
                """,
                version,
                law,
                input.sourceVersionKey(),
                input.effectiveFrom(),
                input.effectiveTo(),
                input.applicabilityStatus(),
                input.sourceUrl(),
                write(input.rawPayload()),
                hash,
                Timestamp.from(Instant.now(clock)));
        for (var article : input.articles()) {
            UUID articleId = UUID.randomUUID();
            jdbc.update(
                    "INSERT INTO law_articles(id,law_version_id,article_key,article_label,source_text,content_hash) VALUES (?,?,?,?,?,?)",
                    articleId,
                    version,
                    article.articleKey(),
                    article.articleLabel(),
                    article.sourceText(),
                    JsonData.hash(article.sourceText()));
            for (int ordinal = 0; ordinal < article.spans().size(); ordinal++) {
                var span = article.spans().get(ordinal);
                String source = SourceText.slice(article.sourceText(), span.start(), span.end());
                jdbc.update(
                        "INSERT INTO law_chunks(id,article_id,chunker_version,ordinal,article_start,article_end,source_text,search_text,content_hash) VALUES (?,?,?,?,?,?,?,?,?)",
                        UUID.randomUUID(),
                        articleId,
                        CHUNKER,
                        ordinal,
                        span.start(),
                        span.end(),
                        source,
                        span.searchText(),
                        JsonData.hash(span.searchText()));
            }
        }
        return loaded(version, false);
    }

    private record Existing(UUID id, String hash) {}

    private Imported loaded(UUID version, boolean reused) {
        return new Imported(
                version,
                jdbc.query(
                        "SELECT id FROM law_articles WHERE law_version_id=? ORDER BY article_key",
                        (rs, n) -> rs.getObject(1, UUID.class),
                        version),
                jdbc.query(
                        "SELECT c.id FROM law_chunks c JOIN law_articles a ON a.id=c.article_id WHERE a.law_version_id=? ORDER BY a.article_key,c.ordinal",
                        (rs, n) -> rs.getObject(1, UUID.class),
                        version),
                reused);
    }

    private String write(Object value) {
        try {
            return canonical.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid import JSON", e);
        }
    }

    private void validate(LawImport input) {
        if (input == null) throw new IllegalArgumentException("Missing import");
        text(input.sourceProvider(), 40);
        text(input.sourceLawId(), 100);
        text(input.sourceVersionKey(), 160);
        text(input.title(), 200);
        if (!List.of("FIXTURE", "LAW_GO_KR").contains(input.sourceProvider())
                || !List.of("VERIFIED", "REVIEW_REQUIRED").contains(input.applicabilityStatus()))
            throw new IllegalArgumentException("Invalid import provenance");
        boolean test = List.of(environment.getActiveProfiles()).contains("test");
        if (input.sourceProvider().equals("FIXTURE")
                && (!test
                        && (!List.of(environment.getActiveProfiles()).contains("local")
                                || input.applicabilityStatus().equals("VERIFIED"))))
            throw new IllegalArgumentException("Verified synthetic corpus is test-only");
        if (List.of(environment.getActiveProfiles()).contains("prod")
                && input.sourceProvider().equals("FIXTURE"))
            throw new IllegalArgumentException("Fixture prohibited in production");
        URI uri = URI.create(input.sourceUrl());
        if (!"https".equals(uri.getScheme())
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || (input.sourceProvider().equals("LAW_GO_KR")
                        && !List.of("law.go.kr", "www.law.go.kr").contains(uri.getHost())))
            throw new IllegalArgumentException("Invalid source URL");
        if (input.effectiveFrom() == null
                || input.effectiveTo() != null
                        && !input.effectiveTo().isAfter(input.effectiveFrom())
                || input.rawPayload() == null
                || input.articles() == null
                || input.articles().isEmpty()
                || input.articles().size() > 10000)
            throw new IllegalArgumentException("Invalid law version");
        var keys = new HashSet<String>();
        for (var article : input.articles()) {
            text(article.articleKey(), 160);
            text(article.articleLabel(), 200);
            text(article.sourceText(), 200000);
            if (!keys.add(article.articleKey())
                    || article.spans() == null
                    || article.spans().isEmpty())
                throw new IllegalArgumentException("Invalid article spans");
            int last = 0;
            for (var span : article.spans()) {
                if (span.start() != last)
                    throw new IllegalArgumentException(
                            "Article spans must cover source consecutively");
                text(SourceText.slice(article.sourceText(), span.start(), span.end()), 20000);
                text(span.searchText(), 30000);
                last = span.end();
            }
            if (last != article.sourceText().codePointCount(0, article.sourceText().length()))
                throw new IllegalArgumentException("Incomplete article coverage");
        }
    }

    private static void text(String value, int max) {
        if (value == null || value.isBlank() || value.codePointCount(0, value.length()) > max)
            throw new IllegalArgumentException("Invalid import text");
    }
}
