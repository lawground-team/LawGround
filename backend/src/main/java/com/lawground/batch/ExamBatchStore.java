package com.lawground.batch;

import com.fasterxml.jackson.databind.JsonNode;
import com.lawground.global.config.JsonData;
import com.lawground.global.error.*;
import com.lawground.job.service.LiveRunPlan;
import com.lawground.question.service.*;
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

/** Frozen input and per-item reconciliation. Repeated refresh never creates another job. */
@Service
public class ExamBatchStore {
    public record Item(
            UUID questionId,
            long questionRevision,
            UUID traceRunId,
            String status,
            String verdict,
            String errorCode) {}

    public record Report(
            UUID id, String status, JsonNode configuration, JsonNode aggregate, List<Item> items) {}

    private final JdbcTemplate jdbc;
    private final JsonData json;
    private final BatchAccess access;
    private final QuestionService questions;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    public ExamBatchStore(
            JdbcTemplate jdbc,
            JsonData json,
            BatchAccess access,
            QuestionService questions,
            Clock clock,
            ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.json = json;
        this.access = access;
        this.questions = questions;
        this.clock = clock;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public Report existing(UUID id, String requestHash) {
        UUID actor = access.required();
        var rows =
                jdbc.queryForList(
                        "SELECT request_hash FROM batch_runs WHERE id=? AND requested_by=?",
                        id,
                        actor);
        if (rows.isEmpty()) return null;
        if (!requestHash.equals(rows.getFirst().get("request_hash")))
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    ErrorCode.IDEMPOTENCY_CONFLICT,
                    "같은 키로 다른 기출 배치를 요청할 수 없습니다.");
        return report(id);
    }

    private boolean approved(UUID id, long revision, boolean lock) {
        return !jdbc.queryForList(
                        "SELECT id FROM questions WHERE id=? AND revision=? AND deleted_at IS NULL AND visibility='PUBLIC' AND source_kind='EXAM_IMPORT' AND answer_source='OFFICIAL_VERIFIED'"
                                + (lock ? " FOR UPDATE" : ""),
                        id,
                        revision)
                .isEmpty();
    }

    @Transactional(readOnly = true)
    public ExamBatchService.Prepared prepare(UUID id) {
        access.required();
        var q = questions.get(id);
        if (!approved(id, q.revision(), false))
            throw ExamBatchService.conflict("출처·정답 검수 후 공개 승인된 기출만 실행할 수 있습니다.");
        var snapshot = json.read(json.write(QuestionSnapshots.from(q)));
        return new ExamBatchService.Prepared(
                id, q.revision(), snapshot, JsonData.hash(json.write(snapshot)), null, null);
    }

    @Transactional
    public Report create(
            UUID id, String requestHash, String version, List<ExamBatchService.Prepared> prepared) {
        UUID actor = access.required();
        jdbc.queryForObject(
                "SELECT pg_advisory_xact_lock(hashtextextended(?,0))",
                Object.class,
                "batch-create:" + id);
        var previous = existing(id, requestHash);
        if (previous != null) return previous;
        var configuration = new LinkedHashMap<String, Object>();
        configuration.put("schemaVersion", 1);
        configuration.put("sourceCommit", access.buildRevision());
        configuration.put("datasetVersion", version);
        configuration.put("pipelineVersion", LiveRunPlan.VERIFIED_PIPELINE);
        configuration.put("tagSetVersion", null);
        configuration.put(
                "items",
                prepared.stream()
                        .map(
                                p -> {
                                    var item = new LinkedHashMap<String, Object>();
                                    item.put("questionId", p.questionId());
                                    item.put("revision", p.revision());
                                    item.put("inputHash", p.hash());
                                    item.put("admission", p.admission());
                                    item.put("admissionError", p.error());
                                    return item;
                                })
                        .toList());
        jdbc.update(
                "INSERT INTO batch_runs(id,requested_by,request_hash,status,config_snapshot) VALUES (?,?,?,'RUNNING',?::jsonb)",
                id,
                actor,
                requestHash,
                json.write(configuration));
        for (var p : prepared) {
            String error =
                    approved(p.questionId(), p.revision(), true)
                            ? p.error()
                            : "SOURCE_CHANGED_OR_REVOKED";
            jdbc.update(
                    "INSERT INTO batch_items(batch_run_id,question_id,question_revision,input_snapshot,input_hash,status,error_code) VALUES (?,?,?,?::jsonb,?,?,?)",
                    id,
                    p.questionId(),
                    p.revision(),
                    json.write(p.snapshot()),
                    p.hash(),
                    error == null ? "PENDING" : "FAILED",
                    error);
            if (error != null) continue;
            var plan = p.admission();
            if (plan == null || !LiveRunPlan.VERIFIED_PIPELINE.equals(plan.pipelineVersion()))
                throw ExamBatchService.invalid();
            UUID trace =
                    UUID.nameUUIDFromBytes(
                            (id + ":question:" + p.questionId()).getBytes(StandardCharsets.UTF_8));
            UUID job = UUID.nameUUIDFromBytes((trace + ":job").getBytes(StandardCharsets.UTF_8));
            Instant now = Instant.now(clock);
            jdbc.update(
                    """
                INSERT INTO trace_runs(id,question_id,requester_id,question_revision,input_snapshot,input_hash,reference_date,operation,idempotency_key,scope,execution_mode,status,pipeline_version,config_snapshot,versions,corpus_manifest,created_at,batch_run_id)
                VALUES (?,?,?,?,?::jsonb,?,?,'BATCH_TRACE_CREATE',?,'BATCH','LIVE','QUEUED',?,?::jsonb,?::jsonb,?::jsonb,?,?)
                """,
                    trace,
                    p.questionId(),
                    actor,
                    p.revision(),
                    json.write(p.snapshot()),
                    p.hash(),
                    LocalDate.parse(p.snapshot().path("referenceDate").asText()),
                    trace,
                    plan.pipelineVersion(),
                    json.write(plan.config()),
                    json.write(plan.versions()),
                    json.write(plan.corpus()),
                    Timestamp.from(now),
                    id);
            jdbc.update(
                    "UPDATE batch_items SET trace_run_id=? WHERE batch_run_id=? AND question_id=?",
                    trace,
                    id,
                    p.questionId());
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

    private Map<String, Object> owned(UUID id, boolean lock) {
        UUID actor = access.required();
        var rows =
                jdbc.queryForList(
                        "SELECT * FROM batch_runs WHERE id=? AND requested_by=?"
                                + (lock ? " FOR UPDATE" : ""),
                        id,
                        actor);
        if (rows.isEmpty())
            throw new ApiException(
                    HttpStatus.NOT_FOUND, ErrorCode.RESOURCE_NOT_FOUND, "배치를 찾을 수 없습니다.");
        return rows.getFirst();
    }

    @Transactional(readOnly = true)
    public Report report(UUID id) {
        var row = owned(id, false);
        return new Report(
                id,
                row.get("status").toString(),
                json.read(row.get("config_snapshot").toString()),
                row.get("aggregate") == null
                        ? aggregate(id)
                        : json.read(row.get("aggregate").toString()),
                items(id));
    }

    private List<Item> items(UUID id) {
        return jdbc.query(
                "SELECT * FROM batch_items WHERE batch_run_id=? ORDER BY question_id",
                (rs, n) ->
                        new Item(
                                rs.getObject("question_id", UUID.class),
                                rs.getLong("question_revision"),
                                rs.getObject("trace_run_id", UUID.class),
                                rs.getString("status"),
                                rs.getString("verdict"),
                                rs.getString("error_code")),
                id);
    }

    @Transactional
    public Report refresh(UUID id) {
        var row = owned(id, true);
        if ("COMPLETED".equals(row.get("status"))) return report(id);
        if (!Objects.equals(
                access.buildRevision(),
                json.read(row.get("config_snapshot").toString()).path("sourceCommit").asText()))
            throw ExamBatchService.conflict("원래 코드 revision에서 배치를 재개하세요.");
        // Worker owns question -> run -> job locks; reconciliation takes only batch -> item locks.
        jdbc.update(
                """
            UPDATE batch_items i SET status=CASE WHEN r.status='COMPLETED' THEN 'SUCCEEDED' ELSE 'FAILED' END,
                verdict=CASE WHEN r.status='COMPLETED' THEN r.verdict ELSE NULL END,
                error_code=CASE WHEN r.status='COMPLETED' THEN NULL ELSE COALESCE(j.last_error_code,r.reason_code,r.status) END
            FROM trace_runs r JOIN processing_jobs j ON j.run_id=r.id
            WHERE i.batch_run_id=? AND i.status='PENDING' AND i.trace_run_id=r.id AND
                ((r.status='COMPLETED' AND j.status='SUCCEEDED') OR (r.status IN ('FAILED','CANCELLED') AND j.status IN ('FAILED','CANCELLED')))
            """,
                id);
        var aggregate = aggregate(id);
        if (aggregate.path("pendingQuestions").asInt() == 0)
            jdbc.update(
                    "UPDATE batch_runs SET status='COMPLETED',aggregate=?::jsonb,completed_at=? WHERE id=?",
                    json.write(aggregate),
                    Timestamp.from(Instant.now(clock)),
                    id);
        return report(id);
    }

    private JsonNode aggregate(UUID id) {
        var items = items(id);
        int total = items.size(),
                succeeded =
                        (int) items.stream().filter(i -> i.status().equals("SUCCEEDED")).count(),
                failed = (int) items.stream().filter(i -> i.status().equals("FAILED")).count(),
                pending = total - succeeded - failed;
        int choices =
                jdbc.queryForObject(
                        "SELECT count(*) FROM choice_analyses a JOIN batch_items i ON i.trace_run_id=a.run_id WHERE i.batch_run_id=? AND i.status='SUCCEEDED'",
                        Integer.class,
                        id);
        var verdicts = new LinkedHashMap<String, Long>();
        for (var v : List.of("SUPPORTED", "HOLD", "REJECTED"))
            verdicts.put(v, items.stream().filter(i -> v.equals(i.verdict())).count());
        var articles =
                jdbc.query(
                        """
            SELECT (e.metadata_snapshot->>'articleId')::uuid AS article_id,count(DISTINCT i.question_id) AS questions
            FROM evidence_results e JOIN batch_items i ON i.trace_run_id=e.run_id WHERE i.batch_run_id=? AND i.status='SUCCEEDED'
            GROUP BY e.metadata_snapshot->>'articleId' ORDER BY e.metadata_snapshot->>'articleId'
            """,
                        (rs, n) ->
                                Map.of(
                                        "articleId",
                                        rs.getObject("article_id", UUID.class),
                                        "questionCount",
                                        rs.getInt("questions")),
                        id);
        var a = new LinkedHashMap<String, Object>();
        a.put("totalQuestions", total);
        a.put("succeededQuestions", succeeded);
        a.put("failedQuestions", failed);
        a.put("pendingQuestions", pending);
        a.put("verdictCounts", verdicts);
        a.put("totalChoiceSlots", total * 5);
        a.put("providedChoiceAnalyses", choices);
        a.put("missingChoiceAnalyses", succeeded * 5 - choices);
        a.put("classifiedChoices", 0);
        a.put("unclassifiedChoices", succeeded * 5);
        a.put("unprocessedChoices", (failed + pending) * 5);
        a.put("classificationStatus", "TAXONOMY_NOT_CONFIRMED");
        a.put("tagSetVersion", null);
        a.put("typeCounts", List.of());
        a.put("articles", articles);
        a.put("countsAreUserErrorRates", false);
        return json.read(json.write(a));
    }
}
