package com.lawground.integration.ai.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Wire models for implemented Python endpoints only; there is no generated legal verdict. */
public final class AiDtos {
    private AiDtos() {}

    public record IdText(UUID id, String text) {
        @Override
        public String toString() {
            return "IdText[content omitted]";
        }
    }

    public record TokenizeRequest(UUID requestId, List<IdText> texts, String tokenizerVersion) {}

    public record TokenItem(UUID id, List<String> tokens) {}

    public record TokenizeResponse(List<TokenItem> items, String tokenizerVersion) {}

    public record EmbedRequest(UUID requestId, List<IdText> texts, String modelRevision) {}

    public record EmbedItem(UUID id, List<Double> vector) {}

    public record EmbedResponse(List<EmbedItem> items, int dim, String modelRevision) {}

    public record RerankCandidate(UUID chunkId, String text) {
        @Override
        public String toString() {
            return "RerankCandidate[content omitted]";
        }
    }

    public record RerankRequest(
            UUID requestId, String query, List<RerankCandidate> candidates, int topK) {
        @Override
        public String toString() {
            return "RerankRequest[content omitted]";
        }
    }

    public record RerankItem(UUID chunkId, double score) {}

    public record RerankResponse(List<RerankItem> ranked, String modelRevision) {}

    public enum ReadyStatus {
        READY,
        NOT_READY
    }

    public record ModelState(String name, String revision, boolean ready) {}

    public record ReadyOutput(ReadyStatus status, List<ModelState> models) {}

    public record ErrorOutput(
            String code,
            String message,
            UUID requestId,
            OffsetDateTime timestamp,
            boolean retryable) {}
}
