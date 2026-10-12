package com.lawground.law.search;

import static org.assertj.core.api.Assertions.*;

import com.lawground.evaluation.EvaluationMetrics;
import com.lawground.law.service.SourceText;
import java.util.*;
import org.junit.jupiter.api.Test;

class SearchMathTest {
    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-000000000001"),
            B = UUID.fromString("00000000-0000-0000-0000-000000000002"),
            C = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");

    @Test
    void rrfUsesRanksDeduplicatesEachChannelAndBreaksTiesLikePostgres() {
        var result = ReciprocalRankFusion.fuse(List.of(A, A, B), List.of(B, A, C));
        assertThat(result)
                .extracting(ReciprocalRankFusion.Ranked::chunkId)
                .containsExactly(A, B, C);
        assertThat(result.get(0).score()).isCloseTo(1.0 / 61 + 1.0 / 62, within(1e-12));
        assertThat(result.get(1).score()).isEqualTo(result.get(0).score());
        assertThat(ReciprocalRankFusion.fuse(List.of(C), List.of(A)))
                .extracting(ReciprocalRankFusion.Ranked::chunkId)
                .containsExactly(A, C);
    }

    @Test
    void emptyChannelAndLimitsAreExplicit() {
        assertThat(ReciprocalRankFusion.fuse(List.of(), List.of(B)))
                .containsExactly(new ReciprocalRankFusion.Ranked(B, 1.0 / 61));
        var ids = new ArrayList<UUID>();
        for (int n = 0; n < 40; n++) ids.add(new UUID(0, n));
        assertThat(ReciprocalRankFusion.fuse(ids, List.of())).hasSize(30);
        assertThat(ReciprocalRankFusion.fuse(List.of(), List.of())).isEmpty();
    }

    @Test
    void vectorAndTokenValidationRejectsInvalidInput() {
        assertThatThrownBy(() -> SearchValues.vector(new float[1023]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SearchValues.vector(new float[1024]))
                .isInstanceOf(IllegalArgumentException.class);
        var vector = new float[1024];
        vector[0] = Float.NaN;
        assertThatThrownBy(() -> SearchValues.vector(vector))
                .isInstanceOf(IllegalArgumentException.class);
        vector[0] = Float.POSITIVE_INFINITY;
        assertThatThrownBy(() -> SearchValues.vector(vector))
                .isInstanceOf(IllegalArgumentException.class);
        vector[0] = 1;
        assertThat(SearchValues.vector(vector)).startsWith("[1.0,");
        assertThatThrownBy(() -> SearchValues.tokens(List.of("두 단어")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void quoteOffsetsAreCodePointsAndRepeatedTextCannotShiftTheRange() {
        String text = "가😀조건\n가😀조건";
        SourceText.requireQuote(text, 1, 4, "😀조건");
        SourceText.requireQuote(text, 6, 9, "😀조건");
        assertThatThrownBy(() -> SourceText.requireQuote(text, 2, 5, "😀조건"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SourceText.slice(text, -1, 4))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void evaluationGroupsArticlesCountsFailuresAndExcludesMock() {
        var result =
                EvaluationMetrics.calculate(
                        List.of(
                                new EvaluationMetrics.Case(
                                        Set.of(A, B),
                                        List.of(A, A, C),
                                        "SUPPORTED",
                                        "SUPPORTED",
                                        false,
                                        "LIVE"),
                                new EvaluationMetrics.Case(
                                        Set.of(), List.of(), "HOLD", "HOLD", false, "LIVE"),
                                new EvaluationMetrics.Case(
                                        Set.of(B), List.of(B), "REJECTED", null, true, "LIVE"),
                                new EvaluationMetrics.Case(
                                        Set.of(B),
                                        List.of(B),
                                        "SUPPORTED",
                                        "SUPPORTED",
                                        false,
                                        "MOCK")));
        assertThat(result.recallAt5()).isEqualTo(.25);
        assertThat(result.hitAt5()).isEqualTo(.5);
        assertThat(result.verificationAccuracy()).isCloseTo(2.0 / 3, within(1e-12));
        assertThat(result.technicalFailures()).isEqualTo(1);
        assertThat(result.holdCases()).isEqualTo(1);
        assertThat(result.excludedMockCases()).isEqualTo(1);
    }

    @Test
    void emptyEvaluationIsUnavailableAndDedupHappensBeforeTopFive() {
        assertThat(EvaluationMetrics.calculate(List.of()).verificationAccuracy()).isNull();
        var d = new UUID(0, 4);
        var e = new UUID(0, 5);
        var f = new UUID(0, 6);
        var result =
                EvaluationMetrics.calculate(
                        List.of(
                                new EvaluationMetrics.Case(
                                        Set.of(e),
                                        List.of(A, A, A, A, A, B, C, d, e, f),
                                        "HOLD",
                                        "HOLD",
                                        false,
                                        "LIVE")));
        assertThat(result.recallAt5()).isEqualTo(1.0);
    }
}
