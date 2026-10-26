package com.lawground.law.search;

import com.lawground.integration.ai.client.AiInternalClient;
import com.lawground.integration.ai.config.AiProperties;
import com.lawground.integration.ai.dto.AiDtos.IdText;
import com.lawground.law.service.LawImportService;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Single-item indexing bounds CPU/memory. Retry calls reuse complete rows; no hidden HTTP retry.
 */
@Service
public class LiveLawIndexService {
    private final LiveIndexStore store;
    private final AiInternalClient client;
    private final AiProperties properties;

    public LiveLawIndexService(
            LiveIndexStore store, AiInternalClient client, AiProperties properties) {
        this.store = store;
        this.client = client;
        this.properties = properties;
    }

    public enum Outcome {
        INDEXED,
        REUSED
    }

    public Outcome indexChunk(UUID chunkId) {
        LiveSearchProfile.outsideTransaction();
        if (chunkId == null) throw new IllegalArgumentException("Chunk required");
        var profile = LiveSearchProfile.from(properties, LawImportService.CHUNKER);
        var source = store.load(chunkId, profile);
        if (store.ready(source, profile)) return Outcome.REUSED;
        var input = List.of(new IdText(chunkId, source.searchText()));
        // Neither read/publish transaction spans either network call.
        var tokens = client.tokenize(UUID.randomUUID(), input).items().getFirst().tokens();
        SearchValues.tokens(tokens);
        var embedding = client.embed(UUID.randomUUID(), input);
        float[] vector = LiveSearchProfile.vector(embedding.items().getFirst().vector());
        return store.publish(source, profile, vector, tokens) ? Outcome.INDEXED : Outcome.REUSED;
    }
}
