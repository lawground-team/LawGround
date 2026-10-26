package com.lawground.evaluation;

import com.lawground.global.error.*;
import com.lawground.job.service.LiveRunPlan;
import com.lawground.law.search.LiveSearchFailure;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Explicit operator invocation. Expected labels never become an AI request. */
@Service
public class EvaluationService {
    record Prepared(GoldenSetService.Case item, LiveRunPlan.Admission admission, String error) {}

    private final EvaluationAccess access;
    private final EvaluationProperties settings;
    private final GoldenSetService golden;
    private final LiveRunPlan plans;
    private final EvaluationStore store;

    public EvaluationService(
            EvaluationAccess access,
            EvaluationProperties settings,
            GoldenSetService golden,
            LiveRunPlan plans,
            EvaluationStore store) {
        this.access = access;
        this.settings = settings;
        this.golden = golden;
        this.plans = plans;
        this.store = store;
    }

    static UUID runId(UUID actor, UUID key) {
        return UUID.nameUUIDFromBytes(
                (actor + ":EVALUATION:" + key).getBytes(StandardCharsets.UTF_8));
    }

    public EvaluationStore.Report start(UUID setId, UUID key) {
        UUID actor = access.requiredManager();
        if (key == null) throw GoldenSetService.invalid();
        UUID id = runId(actor, key);
        var previous = store.existing(id, setId);
        if (previous != null) return previous;
        if (!plans.enabled()
                || !plans.verificationEnabled()
                || settings.buildRevision() == null
                || !settings.buildRevision().matches("[0-9a-f]{40}"))
            throw new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    ErrorCode.REQUEST_FAILED,
                    "실제 평가 설정과 코드 revision을 먼저 고정하세요.");
        var set = golden.get(setId);
        var cases = golden.cases(setId);
        if (!set.status().equals("FROZEN")
                || cases.isEmpty()
                || cases.size() > settings.maxCasesPerRun())
            throw GoldenSetService.conflict("고정된 평가셋과 실행 크기를 확인하세요.");
        var prepared = new ArrayList<Prepared>();
        // Each inspection owns a short DB transaction. A corrupt corpus must not poison the
        // admission transaction.
        for (var c : cases) {
            try {
                var plan = plans.admit(c.snapshot());
                if (!LiveRunPlan.VERIFIED_PIPELINE.equals(plan.pipelineVersion()))
                    throw new IllegalStateException("Verifier plan required");
                prepared.add(
                        new Prepared(
                                c,
                                plan,
                                store.matchesCorpus(c, plan)
                                        ? null
                                        : "EVALUATION_CORPUS_MISMATCH"));
            } catch (ApiException unavailable) {
                if (unavailable.status() != HttpStatus.SERVICE_UNAVAILABLE) throw unavailable;
                prepared.add(new Prepared(c, null, "LAW_OR_INDEX_UNAVAILABLE"));
            } catch (LiveSearchFailure invalid) {
                prepared.add(new Prepared(c, null, invalid.code().name()));
            }
        }
        return store.create(id, setId, prepared);
    }

    public EvaluationStore.Report refresh(UUID id) {
        return store.refresh(id);
    }

    public EvaluationStore.Report get(UUID id) {
        return store.report(id);
    }
}
