package com.lawground.integration.ai.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.validation.annotation.Validated;

/** Internal AI connection settings. Missing secrets/revisions fail closed when called. */
@Validated
@ConfigurationProperties("lawground.ai")
public record AiProperties(
        @NotNull URI baseUrl,
        String apiKey,
        @NotNull Duration connectTimeout,
        @Valid @NotNull Timeouts timeouts,
        Integer maxResponseBytes,
        String embeddingModelRevision,
        String tokenizerVersion,
        String rerankerModelRevision) {
    @ConstructorBinding
    public AiProperties {
        maxResponseBytes = maxResponseBytes == null ? 4 * 1024 * 1024 : maxResponseBytes;
    }

    /** Preserve callers that only configure transport and operation deadlines. */
    public AiProperties(URI baseUrl, String apiKey, Duration connectTimeout, Timeouts timeouts) {
        this(baseUrl, apiKey, connectTimeout, timeouts, null, null, null, null);
    }

    @Override
    public String toString() {
        return "AiProperties[apiKey=REDACTED, connection/revision settings omitted]";
    }

    public record Timeouts(
            @NotNull Duration embed,
            @NotNull Duration tokenize,
            @NotNull Duration hyde,
            @NotNull Duration rerank,
            @NotNull Duration verify,
            @NotNull Duration answer) {}
}
