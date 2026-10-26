package com.lawground.trace.service;

import com.lawground.auth.MemberResolver;
import com.lawground.global.config.JsonData;
import com.lawground.global.error.ApiException;
import com.lawground.global.error.ErrorCode;
import com.lawground.global.web.PageCursor;
import com.lawground.global.web.PageResponse;
import com.lawground.job.service.LiveRunPlan;
import com.lawground.question.dto.QuestionResponse;
import com.lawground.question.service.QuestionService;
import com.lawground.trace.dto.TraceDtos;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AnalysisService {
    public record Cancellation(boolean first, TraceDtos.Cancel body) {}

    public record Submitted(UUID jobId) {}

    private final JdbcTemplate jdbc;
    private final MemberResolver members;
    private final QuestionService questions;
    private final JsonData json;
    private final Clock clock;
    private final PageCursor cursors;
    private final ApplicationEventPublisher events;
    private final boolean mockEnabled;
    private final LiveRunPlan live;
    private final ChoiceAnalysisStore choices;

    public AnalysisService(
            JdbcTemplate jdbc,
            MemberResolver members,
            QuestionService questions,
            JsonData json,
            Clock clock,
            PageCursor cursors,
            ApplicationEventPublisher events,
            LiveRunPlan live,
            ChoiceAnalysisStore choices,
            @Value("${lawground.analysis.mock-enabled:false}") boolean mockEnabled) {
        this.jdbc = jdbc;
        this.members = members;
        this.questions = questions;
        this.json = json;
        this.clock = clock;
        this.cursors = cursors;
        this.events = events;
        this.mockEnabled = mockEnabled;
        this.live = live;
        this.choices = choices;
    }

    @Transactional
    public TraceDtos.Accepted create(UUID questionId, long revision, UUID key) {
        UUID requester = members.requiredMemberId();
        jdbc.queryForObject(
                "SELECT pg_advisory_xact_lock(hashtextextended(?,0))",
                Object.class,
                requester + ":TRACE_CREATE:" + key);
        var owned =
                jdbc.query(
                        "SELECT revision FROM questions WHERE id=? AND owner_id=? AND deleted_at IS NULL FOR UPDATE",
                        (rs, n) -> rs.getLong(1),
                        questionId,
                        requester);
        if (owned.isEmpty()) throw missing();
        var old =
                jdbc.query(
                        "SELECT r.id,j.id AS job_id,r.question_id,r.question_revision FROM trace_runs r JOIN processing_jobs j ON j.run_id=r.id WHERE r.requester_id=? AND r.operation='TRACE_CREATE' AND r.idempotency_key=?",
                        (rs, n) ->
                                new Existing(
                                        rs.getObject("id", UUID.class),
                                        rs.getObject("job_id", UUID.class),
                                        rs.getObject("question_id", UUID.class),
                                        rs.getLong("question_revision")),
                        requester,
                        key);
        if (!old.isEmpty()) {
            var prior = old.get(0);
            if (!prior.questionId().equals(questionId) || prior.revision() != revision)
                throw conflict(ErrorCode.IDEMPOTENCY_CONFLICT, "같은 키로 다른 분석을 요청할 수 없습니다.");
            return accepted(prior.runId(), prior.jobId());
        }
        if (owned.get(0) != revision)
            throw conflict(ErrorCode.VERSION_CONFLICT, "문제가 변경되었습니다. 다시 조회해 주세요.");
        if (!mockEnabled && !live.enabled())
            throw new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    ErrorCode.REQUEST_FAILED,
                    "분석 서비스가 준비되지 않았습니다. 개발 Mock은 별도 활성화가 필요합니다.");
        var question = questions.get(questionId);
        String snapshot = json.write(snapshot(question));
        var admission = live.enabled() ? live.admit(json.read(snapshot)) : null;
        UUID run = UUID.randomUUID(), job = UUID.randomUUID();
        Instant now = Instant.now(clock);
        var versions = new LinkedHashMap<String, Object>();
        for (String field :
                List.of(
                        "pipelineVersion",
                        "embeddingModelRevision",
                        "tokenizerVersion",
                        "promptVersion",
                        "verifierModelRevision",
                        "corpusManifestHash")) versions.put(field, null);
        versions.put("pipelineVersion", "spring-mock-v1");
        String mode = admission == null ? "MOCK" : "LIVE";
        String pipeline = admission == null ? "spring-mock-v1" : admission.pipelineVersion();
        Object config =
                admission == null ? Map.of("hydeEnabled", false, "mock", true) : admission.config();
        jdbc.update(
                """
                INSERT INTO trace_runs(id,question_id,requester_id,question_revision,input_snapshot,input_hash,reference_date,operation,idempotency_key,scope,execution_mode,status,pipeline_version,config_snapshot,versions,corpus_manifest,created_at)
                VALUES (?,?,?,?,?::jsonb,?,?,'TRACE_CREATE',?,'USER',?,'QUEUED',?,?::jsonb,?::jsonb,?::jsonb,?)
                """,
                run,
                questionId,
                requester,
                revision,
                snapshot,
                JsonData.hash(snapshot),
                question.referenceDate(),
                key,
                mode,
                pipeline,
                json.write(config),
                json.write(admission == null ? versions : admission.versions()),
                admission == null ? null : json.write(admission.corpus()),
                Timestamp.from(now));
        jdbc.update(
                "INSERT INTO processing_jobs(id,run_id,status,stage,deadline_at,created_at,updated_at) VALUES (?,?,'PENDING','PREPARE',?,?,?)",
                job,
                run,
                Timestamp.from(now.plusSeconds(600)),
                Timestamp.from(now),
                Timestamp.from(now));
        events.publishEvent(new Submitted(job));
        return accepted(run, job);
    }

    private record Existing(UUID runId, UUID jobId, UUID questionId, long revision) {}

    private TraceDtos.Accepted accepted(UUID run, UUID job) {
        return new TraceDtos.Accepted(
                run, job, "QUEUED", "/api/v1/jobs/" + job, "/api/v1/trace-runs/" + run + "/result");
    }

    private Map<String, Object> snapshot(QuestionResponse q) {
        return com.lawground.question.service.QuestionSnapshots.from(q);
    }

    @Transactional(readOnly = true)
    public TraceDtos.Run get(UUID id) {
        var rows =
                jdbc.query(
                        "SELECT r.* FROM trace_runs r JOIN questions q ON q.id=r.question_id WHERE r.id=? AND r.requester_id=? AND r.scope='USER' AND q.deleted_at IS NULL AND (q.owner_id=? OR q.visibility='PUBLIC')",
                        (rs, n) -> run(rs),
                        id,
                        members.requiredMemberId(),
                        members.requiredMemberId());
        if (rows.isEmpty()) throw missing();
        return rows.get(0);
    }

    @Transactional(readOnly = true)
    public TraceDtos.Job job(UUID id) {
        UUID member = members.requiredMemberId();
        var rows =
                jdbc.query(
                        """
                SELECT j.* FROM processing_jobs j JOIN trace_runs r ON r.id=j.run_id JOIN questions q ON q.id=r.question_id
                WHERE j.id=? AND r.requester_id=? AND r.scope='USER' AND q.deleted_at IS NULL AND (q.owner_id=? OR q.visibility='PUBLIC')
                """,
                        (rs, n) ->
                                new TraceDtos.Job(
                                        rs.getObject("id", UUID.class),
                                        rs.getObject("run_id", UUID.class),
                                        rs.getString("status"),
                                        rs.getString("stage"),
                                        json.read(rs.getString("stage_attempts"))
                                                .path(rs.getString("stage"))
                                                .asInt(0),
                                        instant(rs, "next_attempt_at"),
                                        rs.getString("last_error_code"),
                                        instant(rs, "created_at"),
                                        instant(rs, "updated_at")),
                        id,
                        member,
                        member);
        if (rows.isEmpty()) throw missing();
        return rows.get(0);
    }

    @Transactional(readOnly = true)
    public PageResponse<TraceDtos.Run> list(UUID questionId, int limit, String cursor) {
        questions.get(questionId);
        UUID member = members.requiredMemberId();
        String binding = "runs:" + questionId + ":" + member;
        var position = cursors.decode(cursor, binding);
        var args = new ArrayList<Object>();
        args.add(questionId);
        args.add(member);
        String after = "";
        if (position != null) {
            after = " AND (created_at,id)<(?,?)";
            args.add(Timestamp.from(position.createdAt()));
            args.add(position.id());
        }
        args.add(limit + 1);
        var rows =
                jdbc.query(
                        "SELECT * FROM trace_runs WHERE question_id=? AND requester_id=? AND scope='USER'"
                                + after
                                + " ORDER BY created_at DESC,id DESC LIMIT ?",
                        (rs, n) -> run(rs),
                        args.toArray());
        var items = List.copyOf(rows.subList(0, Math.min(limit, rows.size())));
        var last = items.isEmpty() ? null : items.get(items.size() - 1);
        return new PageResponse<>(
                items,
                rows.size() > limit ? cursors.encode(binding, last.createdAt(), last.id()) : null);
    }

    @Transactional(readOnly = true)
    public TraceDtos.Result result(UUID id) {
        var run = get(id);
        if (!run.status().equals("COMPLETED"))
            throw conflict(ErrorCode.RESULT_NOT_READY, "분석 결과가 아직 준비되지 않았습니다.");
        return jdbc.queryForObject(
                "SELECT * FROM trace_runs WHERE id=?",
                (rs, n) ->
                        new TraceDtos.Result(
                                id,
                                run.questionId(),
                                run.questionRevision(),
                                json.read(rs.getString("input_snapshot")),
                                run.referenceDate(),
                                run.executionMode(),
                                run.verdict(),
                                rs.getString("reason_code"),
                                rs.getString("summary"),
                                evidences(id),
                                choices.read(id),
                                run.versions(),
                                run.completedAt()),
                id);
    }

    private List<TraceDtos.Evidence> evidences(UUID id) {
        return jdbc.query(
                "SELECT * FROM evidence_results WHERE run_id=? ORDER BY ordinal",
                (rs, n) -> {
                    var m = json.read(rs.getString("metadata_snapshot"));
                    return new TraceDtos.Evidence(
                            rs.getObject("id", UUID.class),
                            rs.getObject("chunk_id", UUID.class),
                            UUID.fromString(m.path("articleId").asText()),
                            UUID.fromString(m.path("lawVersionId").asText()),
                            m.path("lawTitle").asText(),
                            m.path("articleLabel").asText(),
                            rs.getString("quote"),
                            rs.getInt("start_offset"),
                            rs.getInt("end_offset"),
                            m.path("sourceUrl").asText(),
                            java.time.LocalDate.parse(m.path("effectiveFrom").asText()),
                            m.path("effectiveTo").isNull()
                                    ? null
                                    : java.time.LocalDate.parse(m.path("effectiveTo").asText()));
                },
                id);
    }

    @Transactional
    public Cancellation cancel(UUID id) {
        UUID member = members.requiredMemberId();
        var qids =
                jdbc.query(
                        "SELECT question_id FROM trace_runs WHERE id=? AND requester_id=? AND scope='USER'",
                        (rs, n) -> rs.getObject(1, UUID.class),
                        id,
                        member);
        if (qids.isEmpty()) throw missing();
        // Shared order with worker and question deletion: question -> run -> job.
        var locks =
                jdbc.query(
                        "SELECT id FROM questions WHERE id=? AND deleted_at IS NULL AND (owner_id=? OR visibility='PUBLIC') FOR UPDATE",
                        (rs, n) -> rs.getObject(1, UUID.class),
                        qids.get(0),
                        member);
        if (locks.isEmpty()) throw missing();
        String status =
                jdbc.queryForObject(
                        "SELECT status FROM trace_runs WHERE id=? FOR UPDATE", String.class, id);
        if (status.equals("CANCELLED"))
            return new Cancellation(false, new TraceDtos.Cancel(id, true, status));
        if (!List.of("QUEUED", "RUNNING").contains(status))
            throw conflict(ErrorCode.VERSION_CONFLICT, "종료된 분석은 취소할 수 없습니다.");
        Timestamp now = Timestamp.from(Instant.now(clock));
        jdbc.update(
                "UPDATE trace_runs SET status='CANCELLED',cancel_requested_at=?,completed_at=? WHERE id=?",
                now,
                now,
                id);
        jdbc.update(
                "UPDATE processing_jobs SET status='CANCELLED',finished_at=?,updated_at=?,lease_owner=NULL,lease_until=NULL WHERE run_id=? AND status NOT IN ('SUCCEEDED','FAILED','CANCELLED')",
                now,
                now,
                id);
        return new Cancellation(true, new TraceDtos.Cancel(id, true, "CANCELLED"));
    }

    private TraceDtos.Run run(ResultSet rs) throws SQLException {
        return new TraceDtos.Run(
                rs.getObject("id", UUID.class),
                rs.getObject("question_id", UUID.class),
                rs.getLong("question_revision"),
                rs.getDate("reference_date").toLocalDate(),
                rs.getString("status"),
                rs.getString("verdict"),
                rs.getString("execution_mode"),
                json.read(rs.getString("versions")),
                instant(rs, "created_at"),
                instant(rs, "completed_at"));
    }

    private static Instant instant(ResultSet rs, String name) throws SQLException {
        var ts = rs.getTimestamp(name);
        return ts == null ? null : ts.toInstant();
    }

    private static ApiException missing() {
        return new ApiException(
                HttpStatus.NOT_FOUND, ErrorCode.RESOURCE_NOT_FOUND, "분석을 찾을 수 없습니다.");
    }

    private static ApiException conflict(ErrorCode code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }
}
