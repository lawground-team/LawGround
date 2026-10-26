package com.lawground.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.lawground.global.config.JsonData;
import com.lawground.global.error.*;
import com.lawground.integration.ai.config.AiProperties;
import com.lawground.integration.ai.config.VerificationProperties;
import com.lawground.job.service.LiveRunPlan;
import com.lawground.trace.service.AnalysisService;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** DB-only evaluation admission/reconciliation. Final labels remain in the frozen set. */
@Service
public class EvaluationStore {
    public record Item(
            UUID caseId,
            UUID traceRunId,
            String status,
            Double recallAt5,
            Boolean hitAt5,
            boolean verdictCorrect,
            String errorCode,
            long latencyMs) {}

    public record Report(
            UUID id,
            UUID setId,
            String status,
            String executionMode,
            String dataOrigin,
            String split,
            int totalCases,
            int succeeded,
            int failed,
            int pending,
            JsonNode versions,
            JsonNode metrics,
            List<Item> items) {}

    private final JdbcTemplate jdbc;
    private final JsonData json;
    private final EvaluationAccess access;
    private final EvaluationProperties settings;
    private final GoldenSetService golden;
    private final AiProperties ai;
    private final VerificationProperties verifier;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    public EvaluationStore(
            JdbcTemplate jdbc,
            JsonData json,
            EvaluationAccess access,
            EvaluationProperties settings,
            GoldenSetService golden,
            AiProperties ai,
            VerificationProperties verifier,
            Clock clock,
            ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.json = json;
        this.access = access;
        this.settings = settings;
        this.golden = golden;
        this.ai = ai;
        this.verifier = verifier;
        this.clock = clock;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public Report existing(UUID id, UUID setId) {
        UUID actor = access.requiredManager();
        var rows =
                jdbc.query(
                        "SELECT set_id FROM evaluation_runs WHERE id=? AND requested_by=?",
                        (rs, n) -> rs.getObject(1, UUID.class),
                        id,
                        actor);
        if (rows.isEmpty()) return null;
        if (!rows.getFirst().equals(setId))
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    ErrorCode.IDEMPOTENCY_CONFLICT,
                    "같은 키로 다른 평가셋을 실행할 수 없습니다.");
        return report(id);
    }

    @Transactional(readOnly = true)
    boolean matchesCorpus(GoldenSetService.Case item, LiveRunPlan.Admission plan) {
        var actual =
                jdbc.query(
                        "SELECT a.law_version_id FROM golden_case_evidences e JOIN law_articles a ON a.id=e.article_id WHERE e.case_id=?",
                        (rs, n) -> rs.getObject(1, UUID.class),
                        item.id());
        var versions = json.read(json.write(plan.corpus())).path("lawVersionIds");
        var selected = new HashSet<UUID>();
        versions.forEach(v -> selected.add(UUID.fromString(v.asText())));
        return selected.containsAll(actual);
    }

    @Transactional
    public Report create(UUID id, UUID setId, List<EvaluationService.Prepared> prepared) {
        UUID actor = access.requiredManager();
        jdbc.queryForObject(
                "SELECT pg_advisory_xact_lock(hashtextextended(?,0))",
                Object.class,
                "evaluation-create:" + id);
        var previous = existing(id, setId);
        if (previous != null) return previous;
        jdbc.queryForList("SELECT id FROM golden_sets WHERE id=? FOR UPDATE", setId);
        var set = golden.get(setId);
        var cases = golden.cases(setId);
        if (!set.status().equals("FROZEN")
                || cases.isEmpty()
                || cases.size() > settings.maxCasesPerRun()
                || !new HashSet<>(prepared.stream().map(p -> p.item().id()).toList())
                        .equals(
                                new HashSet<>(
                                        cases.stream().map(GoldenSetService.Case::id).toList()))
                || prepared.size() != cases.size())
            throw GoldenSetService.conflict("평가셋과 실행 계획이 일치하지 않습니다.");
        var byId = new HashMap<UUID, EvaluationService.Prepared>();
        prepared.forEach(p -> byId.put(p.item().id(), p));
        var versions = new LinkedHashMap<String, Object>();
        versions.put("sourceCommit", settings.buildRevision());
        versions.put("pipelineVersion", LiveRunPlan.VERIFIED_PIPELINE);
        versions.put("embeddingModelRevision", ai.embeddingModelRevision());
        versions.put("tokenizerVersion", ai.tokenizerVersion());
        versions.put("rerankerModelRevision", ai.rerankerModelRevision());
        versions.put("verifierModelName", verifier.modelName());
        versions.put("verifierModelRevision", verifier.modelRevision());
        versions.put("promptVersion", verifier.promptVersion());
        versions.put("goldenSetId", setId);
        versions.put("goldenSetVersion", set.version());
        versions.put("split", set.split().name());
        versions.put("dataOrigin", set.origin().name());
        // A frozen set's IDs, labels and source notes cannot change. Fingerprint is private
        // evaluation metadata only.
        var labels = new ArrayList<Map<String, Object>>();
        for (var c : cases) {
            var entry = new LinkedHashMap<String, Object>();
            entry.put("caseId", c.id());
            entry.put("inputHash", c.inputHash());
            entry.put("leakageGroup", c.leakageGroup());
            entry.put("expectedVerdict", c.expectedVerdict());
            entry.put(
                    "expectedArticles",
                    c.expectedArticles().stream().map(UUID::toString).sorted().toList());
            entry.put("sourceNote", c.sourceNote());
            labels.add(entry);
        }
        versions.put("goldenSetHash", JsonData.hash(json.write(labels)));
        var plans = new LinkedHashMap<String, Object>();
        for (var c : cases) {
            var p = byId.get(c.id());
            var entry = new LinkedHashMap<String, Object>();
            entry.put("inputHash", c.inputHash());
            entry.put("error", p.error());
            entry.put("admission", p.admission());
            plans.put(c.id().toString(), entry);
        }
        jdbc.update(
                "INSERT INTO evaluation_runs(id,set_id,requested_by,status,execution_mode,config_snapshot,versions,started_at) VALUES (?,?,?,'RUNNING','LIVE',?::jsonb,?::jsonb,?)",
                id,
                setId,
                actor,
                json.write(Map.of("schemaVersion", 1, "cases", plans)),
                json.write(versions),
                Timestamp.from(Instant.now(clock)));
        for (var c : cases) {
            var p = byId.get(c.id());
            var state =
                    jdbc.query(
                            "SELECT deleted_at FROM questions WHERE id=? FOR UPDATE",
                            (rs, n) -> rs.getTimestamp(1),
                            c.questionId());
            String error =
                    state.isEmpty() || state.getFirst() != null
                            ? "SOURCE_QUESTION_DELETED"
                            : p.error();
            if (error != null) {
                insertResult(id, setId, c, null, List.of(), null, true, error, 0);
                continue;
            }
            var admission = p.admission();
            if (admission == null
                    || !LiveRunPlan.VERIFIED_PIPELINE.equals(admission.pipelineVersion()))
                throw GoldenSetService.invalid();
            UUID trace =
                    UUID.nameUUIDFromBytes(
                            (id + ":case:" + c.id()).getBytes(StandardCharsets.UTF_8));
            UUID job = UUID.nameUUIDFromBytes((trace + ":job").getBytes(StandardCharsets.UTF_8));
            Instant now = Instant.now(clock);
            jdbc.update(
                    """
                INSERT INTO trace_runs(id,question_id,requester_id,question_revision,input_snapshot,input_hash,reference_date,operation,idempotency_key,scope,execution_mode,status,pipeline_version,config_snapshot,versions,corpus_manifest,created_at,evaluation_run_id,evaluation_case_id)
                SELECT ?,question_id,?,question_revision,input_snapshot,input_hash,(input_snapshot->>'referenceDate')::date,'EVALUATION_TRACE_CREATE',?,'EVALUATION','LIVE','QUEUED',?,?::jsonb,?::jsonb,?::jsonb,?,?,? FROM golden_cases WHERE id=? AND set_id=?
                """,
                    trace,
                    actor,
                    trace,
                    admission.pipelineVersion(),
                    json.write(admission.config()),
                    json.write(admission.versions()),
                    json.write(admission.corpus()),
                    Timestamp.from(now),
                    id,
                    c.id(),
                    c.id(),
                    setId);
            jdbc.update(
                    "INSERT INTO processing_jobs(id,run_id,status,stage,deadline_at,created_at,updated_at) VALUES (?,?,'PENDING','PREPARE',?,?,?)",
                    job,
                    trace,
                    Timestamp.from(now.plusSeconds(600)),
                    Timestamp.from(now),
                    Timestamp.from(now));
            events.publishEvent(new AnalysisService.Submitted(job));
        }
        return report(id);
    }

    private void insertResult(
            UUID run,
            UUID set,
            GoldenSetService.Case c,
            UUID trace,
            List<UUID> ranked,
            String actual,
            boolean failed,
            String error,
            long latency) {
        var m =
                EvaluationMetrics.calculate(
                        List.of(
                                new EvaluationMetrics.Case(
                                        c.expectedArticles(),
                                        ranked,
                                        c.expectedVerdict().name(),
                                        actual,
                                        failed,
                                        "LIVE")));
        jdbc.update(
                "INSERT INTO evaluation_results(evaluation_run_id,set_id,case_id,trace_run_id,status,retrieved_article_ids,recall_at_5,hit_at_5,verdict_correct,error_code,latency_ms,actual_verdict) VALUES (?,?,?,?,?,?::jsonb,?,?,?,?,?,?) ON CONFLICT(evaluation_run_id,case_id) DO NOTHING",
                run,
                set,
                c.id(),
                trace,
                failed ? "FAILED" : "SUCCEEDED",
                json.write(ranked),
                m.recallAt5(),
                m.hitAt5() == null ? null : m.hitAt5() > 0,
                !failed && c.expectedVerdict().name().equals(actual),
                error,
                latency,
                actual);
    }

    private Map<String, Object> owned(UUID id, boolean lock) {
        UUID actor = access.requiredManager();
        var rows =
                jdbc.queryForList(
                        "SELECT e.*,s.data_origin,s.split FROM evaluation_runs e JOIN golden_sets s ON s.id=e.set_id WHERE e.id=? AND e.requested_by=?"
                                + (lock ? " FOR UPDATE OF e" : ""),
                        id,
                        actor);
        if (rows.isEmpty()) throw GoldenSetService.missing();
        return rows.getFirst();
    }

    @Transactional(readOnly = true)
    public Report report(UUID id) {
        var row = owned(id, false);
        UUID set = (UUID) row.get("set_id");
        int total =
                jdbc.queryForObject(
                        "SELECT count(*) FROM golden_cases WHERE set_id=?", Integer.class, set);
        var items =
                jdbc.query(
                        "SELECT * FROM evaluation_results WHERE evaluation_run_id=? ORDER BY case_id",
                        (rs, n) ->
                                new Item(
                                        rs.getObject("case_id", UUID.class),
                                        rs.getObject("trace_run_id", UUID.class),
                                        rs.getString("status"),
                                        (Double) rs.getObject("recall_at_5"),
                                        (Boolean) rs.getObject("hit_at_5"),
                                        rs.getBoolean("verdict_correct"),
                                        rs.getString("error_code"),
                                        rs.getLong("latency_ms")),
                        id);
        int succeeded = (int) items.stream().filter(i -> i.status().equals("SUCCEEDED")).count();
        int failed = items.size() - succeeded;
        return new Report(
                id,
                set,
                row.get("status").toString(),
                row.get("execution_mode").toString(),
                row.get("data_origin").toString(),
                row.get("split").toString(),
                total,
                succeeded,
                failed,
                total - items.size(),
                json.read(row.get("versions").toString()),
                row.get("metrics") == null ? null : json.read(row.get("metrics").toString()),
                items);
    }

    @Transactional
    public Report refresh(UUID id) {
        var row = owned(id, true);
        if (!"LIVE".equals(row.get("execution_mode")))
            throw GoldenSetService.conflict("Mock 실행은 실제 평가 지표에서 제외합니다.");
        if (!java.util.Objects.equals(
                settings.buildRevision(),
                json.read(row.get("versions").toString()).path("sourceCommit").asText()))
            throw GoldenSetService.conflict("평가 코드 revision이 달라 재집계할 수 없습니다.");
        if (Set.of("COMPLETED", "FAILED").contains(row.get("status"))) return report(id);
        UUID set = (UUID) row.get("set_id");
        var cases = golden.cases(set);
        var plans = json.read(row.get("config_snapshot").toString()).path("cases");
        for (var c : cases) {
            if (jdbc.queryForObject(
                    "SELECT EXISTS(SELECT 1 FROM evaluation_results WHERE evaluation_run_id=? AND case_id=?)",
                    Boolean.class,
                    id,
                    c.id())) continue;
            var traces =
                    jdbc.queryForList(
                            "SELECT r.*,j.status AS job_status FROM trace_runs r JOIN processing_jobs j ON j.run_id=r.id WHERE r.evaluation_run_id=? AND r.evaluation_case_id=?",
                            id,
                            c.id());
            if (traces.isEmpty()) {
                insertResult(
                        id, set, c, null, List.of(), null, true, "EVALUATION_TRACE_MISSING", 0);
                continue;
            }
            var trace = traces.getFirst();
            String status = trace.get("status").toString();
            if (Set.of("QUEUED", "RUNNING").contains(status)
                    && !Set.of("FAILED", "CANCELLED").contains(trace.get("job_status"))) continue;
            UUID traceId = (UUID) trace.get("id");
            boolean failed =
                    !status.equals("COMPLETED") || !"SUCCEEDED".equals(trace.get("job_status"));
            String error =
                    failed
                            ? trace.get("reason_code") == null
                                    ? status.equals("CANCELLED")
                                            ? "CANCELLED"
                                            : "EVALUATION_TRACE_FAILED"
                                    : trace.get("reason_code").toString()
                            : null;
            var ranked = new ArrayList<UUID>();
            String actual = trace.get("verdict") == null ? null : trace.get("verdict").toString();
            try {
                var expected = plans.path(c.id().toString()).path("admission");
                var versions = json.read(trace.get("versions").toString());
                if (!c.inputHash().equals(trace.get("input_hash").toString())
                        || !c.snapshot().equals(json.read(trace.get("input_snapshot").toString()))
                        || !versions.equals(expected.path("versions"))
                        || !LiveRunPlan.VERIFIED_PIPELINE.equals(trace.get("pipeline_version")))
                    throw new IllegalArgumentException();
                var hits =
                        json.read(trace.get("checkpoint").toString()).at("/retrieval/result/hits");
                if (!hits.isMissingNode()) {
                    if (!hits.isArray() || hits.size() > 5) throw new IllegalArgumentException();
                    for (var hit : hits) {
                        UUID article = UUID.fromString(hit.path("articleId").asText());
                        if (!ranked.contains(article)) ranked.add(article);
                    }
                }
            } catch (IllegalArgumentException invalid) {
                failed = true;
                error = "EVALUATION_TRACE_INVALID";
                ranked.clear();
                actual = null;
            }
            Instant start = ((Timestamp) trace.get("created_at")).toInstant();
            Instant end =
                    trace.get("completed_at") == null
                            ? Instant.now(clock)
                            : ((Timestamp) trace.get("completed_at")).toInstant();
            insertResult(
                    id,
                    set,
                    c,
                    traceId,
                    List.copyOf(ranked),
                    actual,
                    failed,
                    error,
                    Math.max(0, Duration.between(start, end).toMillis()));
        }
        var progress = report(id);
        if (progress.pending() == 0) {
            var inputs = new ArrayList<EvaluationMetrics.Case>();
            for (var c : cases) {
                var result =
                        jdbc.queryForMap(
                                "SELECT * FROM evaluation_results WHERE evaluation_run_id=? AND case_id=?",
                                id,
                                c.id());
                var ranked = new ArrayList<UUID>();
                json.read(result.get("retrieved_article_ids").toString())
                        .forEach(a -> ranked.add(UUID.fromString(a.asText())));
                inputs.add(
                        new EvaluationMetrics.Case(
                                c.expectedArticles(),
                                ranked,
                                c.expectedVerdict().name(),
                                (String) result.get("actual_verdict"),
                                "FAILED".equals(result.get("status")),
                                "LIVE"));
            }
            var calculated = EvaluationMetrics.calculate(inputs);
            var metrics = new LinkedHashMap<String, Object>();
            metrics.put("metricVersion", "golden-evaluation-v1");
            metrics.put("recallAt5", calculated.recallAt5());
            metrics.put("hitAt5", calculated.hitAt5());
            metrics.put("verificationAccuracy", calculated.verificationAccuracy());
            metrics.put("searchCases", calculated.searchCases());
            metrics.put("verdictCases", calculated.verdictCases());
            metrics.put("technicalFailures", calculated.technicalFailures());
            metrics.put("holdCases", calculated.holdCases());
            metrics.put("excludedMockCases", calculated.excludedMockCases());
            metrics.put("totalCases", cases.size());
            metrics.put("succeeded", progress.succeeded());
            metrics.put("failed", progress.failed());
            metrics.put("pending", 0);
            metrics.put("dataOrigin", progress.dataOrigin());
            metrics.put("split", progress.split());
            jdbc.update(
                    "UPDATE evaluation_runs SET status='COMPLETED',metrics=?::jsonb,completed_at=? WHERE id=?",
                    json.write(metrics),
                    Timestamp.from(Instant.now(clock)),
                    id);
        }
        return report(id);
    }
}
