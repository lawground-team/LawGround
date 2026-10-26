package com.lawground.job.config;

import java.util.Set;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("lawground.analysis.live")
public record LiveAnalysisProperties(
        boolean enabled, Set<UUID> lawIds, Integer candidateLimit, Integer topK) {
    public LiveAnalysisProperties {
        lawIds = lawIds == null ? Set.of() : Set.copyOf(lawIds);
        candidateLimit = candidateLimit == null ? 5 : candidateLimit;
        topK = topK == null ? Math.min(5, candidateLimit) : topK;
        if ((enabled && lawIds.isEmpty())
                || lawIds.size() > 100
                || candidateLimit < 1
                || candidateLimit > 30
                || topK < 1
                || topK > 5
                || topK > candidateLimit)
            throw new IllegalArgumentException(
                    "Explicit live corpus and bounded candidate budget required");
    }
}
