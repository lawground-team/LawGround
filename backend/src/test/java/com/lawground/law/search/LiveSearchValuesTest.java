package com.lawground.law.search;

import static org.assertj.core.api.Assertions.*;

import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;

class LiveSearchValuesTest {
    @Test
    void tokenLimitUsesUnicodeCodePointsAndPreservesNegationNumberOrder() {
        String supplementary = "😀".repeat(200);
        assertThat(SearchValues.tokens(List.of("제32조", "30일", "않", supplementary)))
                .isEqualTo("제32조 30일 않 " + supplementary);
        assertThatThrownBy(() -> SearchValues.tokens(List.of(supplementary + "😀")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SearchValues.tokens(List.of("두 토큰")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SearchValues.tokens(List.of("bad\0token")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void doubleToPgVectorConversionRejectsOverflowUnderflowAndNonUnitVectors() {
        var vector = new ArrayList<>(Collections.nCopies(1024, 0.0));
        vector.set(0, 1.0);
        assertThat(LiveSearchProfile.vector(vector)[0]).isEqualTo(1.0f);
        for (Double value :
                List.of(
                        Double.MAX_VALUE,
                        Double.MIN_VALUE,
                        Double.NaN,
                        Double.POSITIVE_INFINITY,
                        2.0,
                        0.0)) {
            vector.set(0, value);
            assertThatThrownBy(() -> LiveSearchProfile.vector(vector))
                    .isInstanceOf(LiveSearchFailure.class);
        }
    }

    @Test
    void callerMustChooseCorpusDateAndCandidateBudgetWithoutMutableScope() {
        var laws = new HashSet<UUID>();
        laws.add(UUID.randomUUID());
        var query =
                new LiveRetrievalService.Query(
                        laws, LocalDate.of(2025, 1, 1), "query", "article-span-v1", 5, 3);
        laws.clear();
        assertThat(query.lawIds()).hasSize(1);
        assertThat(query.toString()).doesNotContain("query");
        assertThatThrownBy(
                        () ->
                                new LiveSearchProfile(
                                        "mock-model",
                                        "kiwipiepy-0.24.0+rules-1",
                                        "article-span-v1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new LiveSearchProfile(
                                        "a".repeat(40), "mock-tokenizer", "article-span-v1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new LiveSearchProfile(
                                        "a".repeat(40),
                                        "kiwipiepy-0.24.0+rules-1",
                                        "future-chunker"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new LiveRetrievalService.Query(
                                        Set.of(UUID.randomUUID()),
                                        null,
                                        "query",
                                        "article-span-v1",
                                        5,
                                        3))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new LiveRetrievalService.Query(
                                        Set.of(UUID.randomUUID()),
                                        LocalDate.now(),
                                        "query",
                                        "article-span-v1",
                                        2,
                                        3))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
