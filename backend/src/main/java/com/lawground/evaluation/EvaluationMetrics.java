package com.lawground.evaluation;

import java.util.*;

/** Pure calculation over human labels. Does not train models or fabricate quality measurements. */
public final class EvaluationMetrics {
    private EvaluationMetrics() {}

    public record Case(
            Set<UUID> expectedArticles,
            List<UUID> rankedArticles,
            String expectedVerdict,
            String actualVerdict,
            boolean technicalFailure,
            String executionMode) {}

    public record Metrics(
            Double recallAt5,
            Double hitAt5,
            Double verificationAccuracy,
            int searchCases,
            int verdictCases,
            int technicalFailures,
            int holdCases,
            int excludedMockCases) {}

    public static Metrics calculate(List<Case> cases) {
        double recall = 0;
        int hits = 0, search = 0, correct = 0, total = 0, failed = 0, holds = 0, mock = 0;
        for (var item : cases) {
            if (item.executionMode().equals("MOCK")) {
                mock++;
                continue;
            }
            if (!item.executionMode().equals("LIVE")
                    || !Set.of("SUPPORTED", "HOLD", "REJECTED").contains(item.expectedVerdict()))
                throw new IllegalArgumentException("Invalid evaluation case");
            total++;
            if (item.technicalFailure()) failed++;
            if (item.expectedVerdict().equals("HOLD")) holds++;
            if (!item.technicalFailure() && item.expectedVerdict().equals(item.actualVerdict()))
                correct++;
            if (!item.expectedArticles().isEmpty()) {
                search++;
                var top = new LinkedHashSet<UUID>();
                for (UUID article : item.rankedArticles()) {
                    top.add(article);
                    if (top.size() == 5) break;
                }
                long found =
                        item.technicalFailure()
                                ? 0
                                : top.stream().filter(item.expectedArticles()::contains).count();
                recall += (double) found / item.expectedArticles().size();
                if (found > 0) hits++;
            }
        }
        return new Metrics(
                search == 0 ? null : recall / search,
                search == 0 ? null : (double) hits / search,
                total == 0 ? null : (double) correct / total,
                search,
                total,
                failed,
                holds,
                mock);
    }
}
