package com.lawground.batch;

import java.util.*;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("lawground.batch")
public record BatchProperties(
        boolean enabled, Set<UUID> managers, Integer maxItems, String buildRevision) {
    public BatchProperties {
        managers = managers == null ? Set.of() : Set.copyOf(managers);
        maxItems = maxItems == null ? 20 : maxItems;
        if ((enabled && managers.isEmpty())
                || managers.size() > 100
                || maxItems < 1
                || maxItems > 100)
            throw new IllegalArgumentException("Explicit managers and bounded batch size required");
    }
}
