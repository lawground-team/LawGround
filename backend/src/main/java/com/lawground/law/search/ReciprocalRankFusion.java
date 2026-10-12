package com.lawground.law.search;

import java.util.*;

public final class ReciprocalRankFusion {
    public static final int K = 60, CHANNEL_LIMIT = 30, RESULT_LIMIT = 30;

    private ReciprocalRankFusion() {}

    public record Ranked(UUID chunkId, double score) {}

    public static List<Ranked> fuse(List<UUID> dense, List<UUID> lexical) {
        var scores = new HashMap<UUID, Double>();
        for (var channel : List.of(dense, lexical)) {
            var seen = new HashSet<UUID>();
            int rank = 0;
            for (UUID id : channel) {
                Objects.requireNonNull(id);
                if (!seen.add(id)) continue;
                if (++rank > CHANNEL_LIMIT) break;
                scores.merge(id, 1.0 / (K + rank), Double::sum);
            }
        }
        // PostgreSQL uuid uses unsigned byte ordering; UUID.compareTo uses signed longs.
        return scores.entrySet().stream()
                .map(e -> new Ranked(e.getKey(), e.getValue()))
                .sorted(
                        Comparator.comparingDouble(Ranked::score)
                                .reversed()
                                .thenComparing(r -> r.chunkId().toString()))
                .limit(RESULT_LIMIT)
                .toList();
    }
}
