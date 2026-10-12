package com.lawground.trace.service;

import com.lawground.law.service.SourceText;
import com.lawground.trace.dto.TraceDtos;
import java.time.LocalDate;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Resolve source metadata only from the selected DB corpus, never from model-provided labels. */
@Service
public class EvidenceValidator {
    public record Quote(UUID chunkId, String quote, int start, int end) {}

    private final JdbcTemplate jdbc;
    private final boolean isolatedTest;

    public EvidenceValidator(JdbcTemplate jdbc, Environment env) {
        this.jdbc = jdbc;
        this.isolatedTest =
                List.of(env.getActiveProfiles()).contains("test")
                        && !List.of(env.getActiveProfiles()).contains("prod");
    }

    @Transactional(readOnly = true)
    public List<TraceDtos.Evidence> resolve(
            Set<UUID> candidateIds,
            Set<UUID> selectedVersions,
            LocalDate date,
            List<Quote> quotes) {
        if (candidateIds == null
                || selectedVersions == null
                || date == null
                || quotes == null
                || quotes.size() > 50) throw new IllegalArgumentException("Invalid evidence input");
        var seen = new HashSet<String>();
        var result = new ArrayList<TraceDtos.Evidence>();
        for (var quote : quotes) {
            if (quote == null
                    || !candidateIds.contains(quote.chunkId())
                    || !seen.add(quote.chunkId() + ":" + quote.start() + ":" + quote.end()))
                throw new IllegalArgumentException("Unknown or duplicate evidence candidate");
            var rows =
                    jdbc.query(
                            """
                    SELECT c.source_text,a.id AS article_id,a.article_label,v.id AS version_id,v.effective_from,v.effective_to,v.source_url,l.title
                    FROM law_chunks c JOIN law_articles a ON a.id=c.article_id JOIN law_versions v ON v.id=a.law_version_id JOIN laws l ON l.id=v.law_id
                    WHERE c.id=? AND v.applicability_status='VERIFIED' AND v.effective_from<=? AND (v.effective_to IS NULL OR v.effective_to>?) AND l.source_provider=ANY(?::text[])
                    """,
                            (rs, n) -> {
                                SourceText.requireQuote(
                                        rs.getString("source_text"),
                                        quote.start(),
                                        quote.end(),
                                        quote.quote());
                                UUID version = rs.getObject("version_id", UUID.class);
                                if (!selectedVersions.contains(version))
                                    throw new IllegalArgumentException(
                                            "Evidence outside selected corpus");
                                return new TraceDtos.Evidence(
                                        UUID.randomUUID(),
                                        quote.chunkId(),
                                        rs.getObject("article_id", UUID.class),
                                        version,
                                        rs.getString("title"),
                                        rs.getString("article_label"),
                                        quote.quote(),
                                        quote.start(),
                                        quote.end(),
                                        rs.getString("source_url"),
                                        rs.getObject("effective_from", LocalDate.class),
                                        rs.getObject("effective_to", LocalDate.class));
                            },
                            quote.chunkId(),
                            date,
                            date,
                            isolatedTest ? "{LAW_GO_KR,FIXTURE}" : "{LAW_GO_KR}");
            if (rows.size() != 1) throw new IllegalArgumentException("Unavailable evidence source");
            result.add(rows.get(0));
        }
        return List.copyOf(result);
    }
}
