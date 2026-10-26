package com.lawground.integration.ai.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Existing 0.1.0-draft core subset. Choice analysis persistence is a separate feature. */
public final class VerifyDtos {
    private VerifyDtos() {}

    public record Choice(int number, String text) {}

    public record Snapshot(
            String subject,
            String stem,
            String questionType,
            List<Choice> choices,
            int providedAnswerNumber,
            LocalDate referenceDate,
            long revision) {
        @Override
        public String toString() {
            return "VerifySnapshot[content omitted]";
        }
    }

    public record Metadata(
            UUID articleId,
            UUID lawVersionId,
            String lawTitle,
            String articleLabel,
            LocalDate effectiveFrom,
            LocalDate effectiveTo) {}

    public record Candidate(UUID chunkId, String sourceText, Metadata metadata) {
        @Override
        public String toString() {
            return "VerifyCandidate[content omitted]";
        }
    }

    public record Request(
            UUID requestId,
            Snapshot questionSnapshot,
            LocalDate referenceDate,
            List<Candidate> candidates) {
        @Override
        public String toString() {
            return "VerifyRequest[content omitted]";
        }
    }

    public enum Verdict {
        SUPPORTED,
        HOLD,
        REJECTED
    }

    public record Evidence(String evidenceKey, UUID chunkId, String quote, int start, int end) {
        @Override
        public String toString() {
            return "VerifyEvidence[content omitted]";
        }
    }

    public record Response(
            Verdict verdict,
            String reasonCode,
            String summary,
            List<Evidence> evidences,
            JsonNode choiceAnalyses,
            String tagSetVersion,
            String modelRevision,
            String promptVersion) {
        @Override
        public String toString() {
            return "VerifyResponse[content omitted]";
        }
    }
}
