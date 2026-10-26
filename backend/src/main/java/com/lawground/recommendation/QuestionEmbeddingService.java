package com.lawground.recommendation;

import com.lawground.integration.ai.client.AiInternalClient;
import com.lawground.integration.ai.config.AiProperties;
import com.lawground.integration.ai.dto.AiDtos.IdText;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Explicit internal indexing; recommendations never perform this network call on GET. */
@Service
public class QuestionEmbeddingService {
    public enum Outcome {
        INDEXED,
        REUSED
    }

    private final QuestionEmbeddingStore store;
    private final AiInternalClient client;
    private final AiProperties settings;

    public QuestionEmbeddingService(
            QuestionEmbeddingStore store, AiInternalClient client, AiProperties settings) {
        this.store = store;
        this.client = client;
        this.settings = settings;
    }

    public Outcome index(UUID questionId) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Question indexing must run outside a transaction");
        var source = store.load(questionId, settings.embeddingModelRevision());
        if (source.ready()) return Outcome.REUSED;
        var response =
                client.embed(UUID.randomUUID(), List.of(new IdText(source.id(), source.input())));
        var values = response.items().getFirst().vector();
        var vector = new float[1024];
        if (values.size() != vector.length)
            throw new IllegalArgumentException("Expected1024 dimensions");
        for (int i = 0; i < vector.length; i++) {
            double n = values.get(i);
            if (!Double.isFinite(n) || Math.abs(n) > Float.MAX_VALUE)
                throw new IllegalArgumentException("Invalid vector");
            vector[i] = (float) n;
        }
        return store.publish(source, vector) ? Outcome.INDEXED : Outcome.REUSED;
    }
}
