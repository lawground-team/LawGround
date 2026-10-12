package com.lawground.law.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Internal reviewed import command; deliberately has no public HTTP endpoint. */
public record LawImport(
        String sourceProvider,
        String sourceLawId,
        String sourceVersionKey,
        String title,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        String applicabilityStatus,
        String sourceUrl,
        Map<String, Object> rawPayload,
        List<Article> articles) {
    public record Article(
            String articleKey, String articleLabel, String sourceText, List<Span> spans) {}

    /**
     * Contiguous article code-point range, with optional heading/condition enrichment for search.
     */
    public record Span(int start, int end, String searchText) {}
}
