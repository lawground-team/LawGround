package com.lawground.evaluation;

import java.util.*;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("lawground.evaluation")
public record EvaluationProperties(
        boolean enabled, Set<UUID> managers, Integer maxCasesPerRun, String buildRevision) {
    public EvaluationProperties {
        managers = managers == null ? Set.of() : Set.copyOf(managers);
        maxCasesPerRun = maxCasesPerRun == null ? 20 : maxCasesPerRun;
        if (enabled && managers.isEmpty()
                || managers.size() > 100
                || maxCasesPerRun < 1
                || maxCasesPerRun > 100)
            throw new IllegalArgumentException(
                    "Explicit evaluation managers and bounded run size required");
    }
}
