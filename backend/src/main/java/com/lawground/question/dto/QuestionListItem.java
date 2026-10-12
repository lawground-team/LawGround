package com.lawground.question.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record QuestionListItem(
        UUID id,
        String stemPreview,
        String subject,
        LocalDate referenceDate,
        String visibility,
        long revision,
        Instant createdAt,
        RunSummary latestOwnRun) {
    public record RunSummary(UUID id, String status, String verdict, String executionMode) {}
}
