package com.lawground.batch;

import com.fasterxml.jackson.databind.JsonNode;
import com.lawground.global.config.JsonData;
import com.lawground.global.error.*;
import com.lawground.job.service.LiveRunPlan;
import com.lawground.law.search.LiveSearchFailure;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Explicit in-process operator entrypoint. Never a public HTTP administration API. */
@Service
public class ExamBatchService {
    record Prepared(
            UUID questionId,
            long revision,
            JsonNode snapshot,
            String hash,
            LiveRunPlan.Admission admission,
            String error) {}

    private final BatchAccess access;
    private final BatchProperties settings;
    private final ExamBatchStore store;
    private final LiveRunPlan plans;
    private final JsonData json;

    public ExamBatchService(
            BatchAccess access,
            BatchProperties settings,
            ExamBatchStore store,
            LiveRunPlan plans,
            JsonData json) {
        this.access = access;
        this.settings = settings;
        this.store = store;
        this.plans = plans;
        this.json = json;
    }

    public ExamBatchStore.Report start(
            UUID requestKey, String datasetVersion, List<UUID> questionIds) {
        UUID actor = access.required();
        if (requestKey == null
                || datasetVersion == null
                || !datasetVersion.matches("[A-Za-z0-9._-]{1,100}")
                || questionIds == null
                || questionIds.isEmpty()
                || questionIds.stream().anyMatch(Objects::isNull)
                || questionIds.size() > settings.maxItems()
                || new HashSet<>(questionIds).size() != questionIds.size()) throw invalid();
        var ids = questionIds.stream().sorted().toList();
        String requestHash =
                JsonData.hash(
                        json.write(Map.of("datasetVersion", datasetVersion, "questionIds", ids)));
        UUID id =
                UUID.nameUUIDFromBytes(
                        (actor + ":BATCH:" + requestKey).getBytes(StandardCharsets.UTF_8));
        var old = store.existing(id, requestHash);
        if (old != null) return old;
        if (!plans.enabled()
                || !plans.verificationEnabled()
                || settings.buildRevision() == null
                || !settings.buildRevision().matches("[0-9a-f]{40}"))
            throw new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    ErrorCode.REQUEST_FAILED,
                    "LIVE 검증 및 코드 revision 고정이 필요합니다.");
        var prepared = new ArrayList<Prepared>();
        for (var q : ids) {
            var item = store.prepare(q);
            try {
                var admission = plans.admit(item.snapshot());
                if (!LiveRunPlan.VERIFIED_PIPELINE.equals(admission.pipelineVersion()))
                    throw invalid();
                prepared.add(
                        new Prepared(
                                q, item.revision(), item.snapshot(), item.hash(), admission, null));
            } catch (ApiException failure) {
                if (failure.status() != HttpStatus.SERVICE_UNAVAILABLE) throw failure;
                prepared.add(
                        new Prepared(
                                q,
                                item.revision(),
                                item.snapshot(),
                                item.hash(),
                                null,
                                "LAW_OR_INDEX_UNAVAILABLE"));
            } catch (LiveSearchFailure failure) {
                prepared.add(
                        new Prepared(
                                q,
                                item.revision(),
                                item.snapshot(),
                                item.hash(),
                                null,
                                failure.code().name()));
            }
        }
        return store.create(id, requestHash, datasetVersion, prepared);
    }

    public ExamBatchStore.Report refresh(UUID id) {
        return store.refresh(id);
    }

    public ExamBatchStore.Report get(UUID id) {
        return store.report(id);
    }

    static ApiException invalid() {
        return new ApiException(
                HttpStatus.BAD_REQUEST,
                ErrorCode.VALIDATION_FAILED,
                "고정 기출 버전·서로 다른 승인 문제·실행 크기를 확인하세요.");
    }

    static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, ErrorCode.VERSION_CONFLICT, message);
    }
}
