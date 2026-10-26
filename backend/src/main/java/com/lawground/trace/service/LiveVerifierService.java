package com.lawground.trace.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.lawground.integration.ai.client.AiInternalClient;
import com.lawground.integration.ai.dto.VerifyDtos.Response;
import com.lawground.job.service.LiveRunPlan.VerifiedConfig;
import com.lawground.law.search.LiveRetrievalService;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class LiveVerifierService {
    private final VerificationEvidence evidence;
    private final AiInternalClient client;

    public LiveVerifierService(VerificationEvidence evidence, AiInternalClient client) {
        this.evidence = evidence;
        this.client = client;
    }

    public record Attempt(String requestHash, Response response) {}

    public Attempt verify(
            JsonNode snapshot, VerifiedConfig config, LiveRetrievalService.Result retrieval) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Verifier HTTP cannot run in a DB transaction");
        var request =
                evidence.prepare(UUID.randomUUID(), snapshot, config.retrieval(), retrieval.hits());
        String hash = evidence.fingerprint(request);
        return new Attempt(hash, client.verify(request, config.verification()));
    }
}
