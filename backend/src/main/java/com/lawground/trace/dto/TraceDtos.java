package com.lawground.trace.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class TraceDtos {
    private TraceDtos() {}

    public record Input(@NotNull @Min(1) Long questionRevision) {}

    public record Accepted(
            UUID runId, UUID jobId, String status, String statusUrl, String resultUrl) {}

    public record Run(
            UUID id,
            UUID questionId,
            long questionRevision,
            LocalDate referenceDate,
            String status,
            String verdict,
            String executionMode,
            JsonNode versions,
            Instant createdAt,
            Instant completedAt) {}

    public record Job(
            UUID id,
            UUID runId,
            String status,
            String stage,
            int stageAttempt,
            Instant nextAttemptAt,
            String errorCode,
            Instant createdAt,
            Instant updatedAt) {}

    public record Result(
            UUID runId,
            UUID questionId,
            long questionRevision,
            JsonNode inputSnapshot,
            LocalDate referenceDate,
            String executionMode,
            String verdict,
            String reasonCode,
            String summary,
            List<Evidence> evidences,
            Object choiceAnalyses,
            JsonNode versions,
            Instant completedAt) {}

    public record Evidence(
            UUID evidenceId,
            UUID chunkId,
            UUID articleId,
            UUID lawVersionId,
            String lawTitle,
            String articleLabel,
            String quote,
            int start,
            int end,
            String sourceUrl,
            LocalDate effectiveFrom,
            LocalDate effectiveTo) {}

    public record Cancel(UUID runId, boolean cancelRequested, String status) {}
}
