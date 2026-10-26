package com.lawground.law.search;

import com.lawground.integration.ai.config.AiProperties;
import java.util.List;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Exact local connection pins, not revisions adopted from remote readiness. */
public record LiveSearchProfile(String model, String tokenizer, String chunker) {
    public LiveSearchProfile {
        if (model == null || !model.matches("[0-9a-f]{40}"))
            throw new IllegalArgumentException("Pinned embedding commit required");
        SearchValues.revision(tokenizer);
        SearchValues.revision(chunker);
        if (!tokenizer.startsWith("kiwipiepy-") || !chunker.equals("article-span-v1"))
            throw new IllegalArgumentException("Approved tokenizer/chunker required");
    }

    public static LiveSearchProfile from(AiProperties properties, String chunker) {
        return new LiveSearchProfile(
                properties.embeddingModelRevision(), properties.tokenizerVersion(), chunker);
    }

    static void outsideTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new LiveSearchFailure(LiveSearchFailure.Code.TRANSACTION_FORBIDDEN);
    }

    static float[] vector(List<Double> values) {
        if (values == null || values.size() != 1024)
            throw new LiveSearchFailure(LiveSearchFailure.Code.INVALID_INDEX);
        float[] result = new float[1024];
        double norm = 0;
        for (int i = 0; i < result.length; i++) {
            Double value = values.get(i);
            if (value == null || !Double.isFinite(value) || Math.abs(value) > Float.MAX_VALUE)
                throw new LiveSearchFailure(LiveSearchFailure.Code.INVALID_INDEX);
            result[i] = value.floatValue();
            norm += (double) result[i] * result[i];
        }
        if (Math.abs(norm - 1) > 0.001)
            throw new LiveSearchFailure(LiveSearchFailure.Code.INVALID_INDEX);
        SearchValues.vector(result);
        return result;
    }
}
