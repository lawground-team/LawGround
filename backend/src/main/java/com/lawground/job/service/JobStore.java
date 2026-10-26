package com.lawground.job.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.lawground.global.config.JsonData;
import com.lawground.law.search.LiveRetrievalService;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** PostgreSQL is authoritative; all competing mutations lock question -> run -> job. */
@Service
public class JobStore {
    private final JdbcTemplate jdbc;
    private final JsonData json;
    private final Clock clock;
    private final com.lawground.trace.service.VerificationEvidence verification;
    private final LiveRunPlan plans;
    private static final List<String> FINAL = List.of("SUCCEEDED", "FAILED", "CANCELLED");

    public enum Stage {
        PREPARE,
        HYDE,
        SEARCH,
        RERANK,
        VERIFY,
        PERSIST
    }

    public enum Start {
        STARTED,
        SKIP,
        LOST
    }

    public record Claim(
            UUID jobId,
            UUID runId,
            UUID questionId,
            long fencingToken,
            Instant deadline,
            JsonNode snapshot) {}

    public JobStore(
            JdbcTemplate jdbc,
            JsonData json,
            Clock clock,
            com.lawground.trace.service.VerificationEvidence verification,
            LiveRunPlan plans) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
        this.verification = verification;
        this.plans = plans;
    }

    private Map<String, Object> lock(UUID id) {
        var ids =
                jdbc.query(
                        "SELECT r.question_id,r.id FROM processing_jobs j JOIN trace_runs r ON r.id=j.run_id WHERE j.id=?",
                        (rs, n) ->
                                new UUID[] {
                                    rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)
                                },
                        id);
        if (ids.isEmpty()) return null;
        jdbc.queryForList("SELECT id FROM questions WHERE id=? FOR UPDATE", ids.get(0)[0]);
        jdbc.queryForList("SELECT id FROM trace_runs WHERE id=? FOR UPDATE", ids.get(0)[1]);
        return jdbc.queryForMap(
                """
                SELECT j.*,r.question_id,r.status AS run_status,r.execution_mode,r.cancel_requested_at,r.checkpoint,r.input_snapshot,r.input_hash,r.pipeline_version,r.config_snapshot,r.requester_id,q.owner_id,q.deleted_at,m.status AS member_status
                FROM processing_jobs j JOIN trace_runs r ON r.id=j.run_id JOIN questions q ON q.id=r.question_id JOIN members m ON m.id=r.requester_id
                WHERE j.id=? FOR UPDATE OF j
                """,
                id);
    }

    private Instant time(Map<String, Object> row, String key) {
        var value = row.get(key);
        return value == null ? null : ((Timestamp) value).toInstant();
    }

    private Timestamp now() {
        return Timestamp.from(Instant.now(clock));
    }

    private boolean usable(Map<String, Object> row, Claim claim) {
        Instant now = Instant.now(clock);
        return row != null
                && "RUNNING".equals(row.get("status"))
                && ((Number) row.get("fencing_token")).longValue() == claim.fencingToken()
                && row.get("deleted_at") == null
                && row.get("cancel_requested_at") == null
                && "RUNNING".equals(row.get("run_status"))
                && time(row, "lease_until").isAfter(now)
                && time(row, "deadline_at").isAfter(now);
    }

    @Transactional
    public Claim claim(UUID id, String worker) {
        return claim(id, worker, "MOCK");
    }

    @Transactional
    public Claim claim(UUID id, String worker, String expectedMode) {
        var row = lock(id);
        if (row == null
                || !expectedMode.equals(row.get("execution_mode"))
                || FINAL.contains(row.get("status"))) return null;
        Instant now = Instant.now(clock);
        if (row.get("deleted_at") != null
                || row.get("cancel_requested_at") != null
                || "CANCELLED".equals(row.get("run_status"))) {
            terminal(row, "CANCELLED", "CANCELLED");
            return null;
        }
        if (!time(row, "deadline_at").isAfter(now)) {
            terminal(row, "FAILED", "DEADLINE_EXCEEDED");
            return null;
        }
        if ("LIVE".equals(expectedMode) && !authorized(row)) {
            terminal(row, "FAILED", "ACCESS_DENIED");
            return null;
        }
        if ("RUNNING".equals(row.get("status")) && time(row, "lease_until").isAfter(now))
            return null;
        if ("RETRY_WAIT".equals(row.get("status")) && time(row, "next_attempt_at").isAfter(now))
            return null;
        long fence = ((Number) row.get("fencing_token")).longValue() + 1;
        Instant until =
                now.plusSeconds(180).isBefore(time(row, "deadline_at"))
                        ? now.plusSeconds(180)
                        : time(row, "deadline_at");
        jdbc.update(
                "UPDATE processing_jobs SET status='RUNNING',fencing_token=?,lease_owner=?,lease_until=?,heartbeat_at=?,next_attempt_at=NULL,delivery_count=delivery_count+1,updated_at=? WHERE id=?",
                fence,
                worker,
                Timestamp.from(until),
                Timestamp.from(now),
                Timestamp.from(now),
                id);
        jdbc.update(
                "UPDATE trace_runs SET status='RUNNING',started_at=COALESCE(started_at,?) WHERE id=? AND status IN ('QUEUED','RUNNING')",
                Timestamp.from(now),
                row.get("run_id"));
        return new Claim(
                id,
                (UUID) row.get("run_id"),
                (UUID) row.get("question_id"),
                fence,
                time(row, "deadline_at"),
                json.read(row.get("input_snapshot").toString()));
    }

    private boolean authorized(Map<String, Object> row) {
        return "ACTIVE".equals(row.get("member_status"))
                && row.get("requester_id").equals(row.get("owner_id"));
    }

    private boolean liveUsable(Map<String, Object> row, Claim claim) {
        if (!usable(row, claim) || !"LIVE".equals(row.get("execution_mode"))) return false;
        if (!authorized(row)) {
            terminal(row, "FAILED", "ACCESS_DENIED");
            return false;
        }
        return true;
    }

    public record LiveWork(String pipelineVersion, JsonNode config, JsonNode checkpoint) {}

    @Transactional
    public LiveWork liveWork(Claim claim) {
        var row = lock(claim.jobId());
        if (!liveUsable(row, claim)) return null;
        // JSONB normalizes whitespace/key order; compare the semantic frozen snapshot, never the
        // current question.
        if (!json.read(row.get("input_snapshot").toString()).equals(claim.snapshot()))
            throw new LiveWorkerFailure(LiveWorkerFailure.Code.SNAPSHOT_INVALID);
        return new LiveWork(
                row.get("pipeline_version").toString(),
                json.read(row.get("config_snapshot").toString()),
                json.read(row.get("checkpoint").toString()));
    }

    @Transactional
    public boolean recordLiveRetrieval(Claim claim, LiveRetrievalService.Result result) {
        var row = lock(claim.jobId());
        if (!liveUsable(row, claim)) return false;
        var checkpoint =
                (com.fasterxml.jackson.databind.node.ObjectNode)
                        json.read(row.get("checkpoint").toString());
        checkpoint.put("SEARCH", true);
        checkpoint.put("RERANK", result.status() == LiveRetrievalService.Status.RETRIEVED);
        checkpoint.set(
                "retrieval",
                json.read(
                        json.write(
                                Map.of(
                                        "schemaVersion",
                                        1,
                                        "mode",
                                        "RETRIEVAL_ONLY",
                                        "verificationPerformed",
                                        false,
                                        "result",
                                        result))));
        jdbc.update(
                "UPDATE trace_runs SET checkpoint=?::jsonb WHERE id=?",
                json.write(checkpoint),
                claim.runId());
        return true;
    }

    @Transactional
    public boolean failLive(Claim claim, LiveWorkerFailure.Code code) {
        var row = lock(claim.jobId());
        if (!liveUsable(row, claim)) return false;
        terminal(row, "FAILED", code.name());
        return true;
    }

    private void verifiedRun(
            Map<String, Object> row, Claim claim, LiveRunPlan.VerifiedConfig config) {
        if (!LiveRunPlan.VERIFIED_PIPELINE.equals(row.get("pipeline_version"))
                || !plans.readVerified(json.read(row.get("config_snapshot").toString()))
                        .equals(config)
                || !json.read(row.get("input_snapshot").toString()).equals(claim.snapshot()))
            throw new LiveWorkerFailure(LiveWorkerFailure.Code.CHECKPOINT_INVALID);
    }

    @Transactional
    public boolean recordVerification(
            Claim claim,
            LiveRunPlan.VerifiedConfig config,
            LiveRetrievalService.Result retrieval,
            com.lawground.trace.service.LiveVerifierService.Attempt attempt) {
        var row = lock(claim.jobId());
        if (!liveUsable(row, claim)) return false;
        verifiedRun(row, claim, config);
        if (!"VERIFY".equals(row.get("stage")))
            throw new LiveWorkerFailure(LiveWorkerFailure.Code.CHECKPOINT_INVALID);
        var request =
                verification.prepareLocked(
                        UUID.randomUUID(), claim.snapshot(), config.retrieval(), retrieval.hits());
        if (attempt == null || !verification.fingerprint(request).equals(attempt.requestHash()))
            throw new LiveWorkerFailure(LiveWorkerFailure.Code.CORPUS_CHANGED);
        var checkpoint =
                (com.fasterxml.jackson.databind.node.ObjectNode)
                        json.read(row.get("checkpoint").toString());
        checkpoint.put("VERIFY", true);
        checkpoint.set(
                "verification",
                json.read(
                        json.write(
                                verification.checkpoint(
                                        attempt.response(), request, config.verification()))));
        jdbc.update(
                "UPDATE trace_runs SET checkpoint=?::jsonb WHERE id=?",
                json.write(checkpoint),
                claim.runId());
        return true;
    }

    @Transactional
    public boolean completeVerified(
            Claim claim, LiveRunPlan.VerifiedConfig config, LiveRetrievalService.Result retrieval) {
        var row = lock(claim.jobId());
        if (!liveUsable(row, claim)) return false;
        verifiedRun(row, claim, config);
        var checkpoint = json.read(row.get("checkpoint").toString());
        if (!"PERSIST".equals(row.get("stage"))
                || !checkpoint.path("VERIFY").asBoolean(false)
                || checkpoint.path("PERSIST").asBoolean(false))
            throw new LiveWorkerFailure(LiveWorkerFailure.Code.CHECKPOINT_INVALID);
        var request =
                verification.prepareLocked(
                        UUID.randomUUID(), claim.snapshot(), config.retrieval(), retrieval.hits());
        var response =
                verification.fromCheckpoint(
                        checkpoint.path("verification"), request, config.verification());
        var evidences = verification.resolve(response, request, config.retrieval());
        int ordinal = 0;
        for (var e : evidences) {
            var metadata = new java.util.LinkedHashMap<String, Object>();
            metadata.put("articleId", e.articleId());
            metadata.put("lawVersionId", e.lawVersionId());
            metadata.put("lawTitle", e.lawTitle());
            metadata.put("articleLabel", e.articleLabel());
            metadata.put("sourceUrl", e.sourceUrl());
            metadata.put("effectiveFrom", e.effectiveFrom());
            metadata.put("effectiveTo", e.effectiveTo());
            jdbc.update(
                    "INSERT INTO evidence_results(id,run_id,chunk_id,ordinal,quote,start_offset,end_offset,metadata_snapshot) VALUES (?,?,?,?,?,?,?,?::jsonb)",
                    e.evidenceId(),
                    claim.runId(),
                    e.chunkId(),
                    ordinal++,
                    e.quote(),
                    e.start(),
                    e.end(),
                    json.write(metadata));
        }
        Timestamp now = now();
        jdbc.update(
                "UPDATE trace_runs SET status='COMPLETED',verdict=?,reason_code=?,summary=?,completed_at=?,checkpoint=jsonb_set(checkpoint,'{PERSIST}','true'::jsonb,true) WHERE id=?",
                response.verdict().name(),
                response.reasonCode(),
                response.summary(),
                now,
                claim.runId());
        jdbc.update(
                "UPDATE processing_jobs SET status='SUCCEEDED',finished_at=?,updated_at=?,lease_owner=NULL,lease_until=NULL,last_error_code=NULL WHERE id=? AND fencing_token=?",
                now,
                now,
                claim.jobId(),
                claim.fencingToken());
        return true;
    }

    @Transactional(readOnly = true)
    public String publicationMode(UUID id) {
        var rows =
                jdbc.query(
                        "SELECT r.execution_mode FROM processing_jobs j JOIN trace_runs r ON r.id=j.run_id WHERE j.id=?",
                        (rs, n) -> rs.getString(1),
                        id);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    @Transactional
    public boolean heartbeat(Claim claim) {
        Instant now = Instant.now(clock),
                until =
                        now.plusSeconds(180).isBefore(claim.deadline())
                                ? now.plusSeconds(180)
                                : claim.deadline();
        return jdbc.update(
                        "UPDATE processing_jobs SET lease_until=?,heartbeat_at=?,updated_at=? WHERE id=? AND status='RUNNING' AND fencing_token=? AND lease_until>? AND deadline_at>?",
                        Timestamp.from(until),
                        Timestamp.from(now),
                        Timestamp.from(now),
                        claim.jobId(),
                        claim.fencingToken(),
                        Timestamp.from(now),
                        Timestamp.from(now))
                == 1;
    }

    @Transactional
    public Start beginStage(Claim claim, Stage stage) {
        var row = lock(claim.jobId());
        if (!usable(row, claim)) return Start.LOST;
        if ("LIVE".equals(row.get("execution_mode")) && !authorized(row)) {
            terminal(row, "FAILED", "ACCESS_DENIED");
            return Start.LOST;
        }
        if (json.read(row.get("checkpoint").toString()).path(stage.name()).asBoolean(false))
            return Start.SKIP;
        int attempt = json.read(row.get("stage_attempts").toString()).path(stage.name()).asInt(0);
        if (attempt >= 3) {
            terminal(row, "FAILED", "ATTEMPTS_EXHAUSTED");
            return Start.LOST;
        }
        jdbc.update(
                "UPDATE processing_jobs SET stage=?,stage_attempts=jsonb_set(stage_attempts,ARRAY[?::text],to_jsonb(?::int),true),updated_at=? WHERE id=? AND fencing_token=?",
                stage.name(),
                stage.name(),
                attempt + 1,
                now(),
                claim.jobId(),
                claim.fencingToken());
        return Start.STARTED;
    }

    @Transactional
    public boolean finishStage(Claim claim, Stage stage) {
        var row = lock(claim.jobId());
        if (!usable(row, claim)) return false;
        if ("LIVE".equals(row.get("execution_mode")) && !authorized(row)) {
            terminal(row, "FAILED", "ACCESS_DENIED");
            return false;
        }
        jdbc.update(
                "UPDATE trace_runs SET checkpoint=jsonb_set(checkpoint,ARRAY[?::text],'true'::jsonb,true) WHERE id=?",
                stage.name(),
                claim.runId());
        return true;
    }

    @Transactional
    public boolean completeMock(Claim claim) {
        var row = lock(claim.jobId());
        if (!usable(row, claim) || !"MOCK".equals(row.get("execution_mode"))) return false;
        Timestamp now = now();
        jdbc.update(
                "UPDATE trace_runs SET status='COMPLETED',verdict='HOLD',reason_code='MOCK_ONLY',summary=?,completed_at=? WHERE id=? AND status='RUNNING'",
                "개발 Mock의 큐·저장 흐름 검증 결과입니다. 실제 법령 검색이나 AI 판정은 수행하지 않았습니다.",
                now,
                claim.runId());
        jdbc.update(
                "UPDATE processing_jobs SET status='SUCCEEDED',finished_at=?,updated_at=?,lease_owner=NULL,lease_until=NULL,last_error_code=NULL WHERE id=? AND fencing_token=? AND status='RUNNING'",
                now,
                now,
                claim.jobId(),
                claim.fencingToken());
        return true;
    }

    @Transactional
    public boolean retry(Claim claim, String errorCode, Instant retryAfter) {
        if (!List.of("MOCK_TRANSIENT", "AI_UNAVAILABLE", "AI_CONTRACT_INVALID", "WORKER_FAILURE")
                .contains(errorCode)) throw new IllegalArgumentException("Unknown worker error");
        var row = lock(claim.jobId());
        if (!usable(row, claim)) return false;
        if ("LIVE".equals(row.get("execution_mode")) && !authorized(row)) {
            terminal(row, "FAILED", "ACCESS_DENIED");
            return false;
        }
        int attempts =
                json.read(row.get("stage_attempts").toString())
                        .path(row.get("stage").toString())
                        .asInt(0);
        if (errorCode.equals("AI_CONTRACT_INVALID") || attempts >= 3) {
            terminal(row, "FAILED", errorCode);
            return true;
        }
        Instant next =
                Instant.now(clock)
                        .plusMillis(
                                (attempts <= 1 ? 5000 : 20000)
                                        + ThreadLocalRandom.current().nextInt(1001));
        if (retryAfter != null && retryAfter.isAfter(next)) next = retryAfter;
        if (!next.isBefore(claim.deadline())) {
            terminal(row, "FAILED", "DEADLINE_EXCEEDED");
            return true;
        }
        jdbc.update(
                "UPDATE processing_jobs SET status='RETRY_WAIT',next_attempt_at=?,last_error_code=?,lease_owner=NULL,lease_until=NULL,updated_at=? WHERE id=? AND fencing_token=?",
                Timestamp.from(next),
                errorCode,
                now(),
                claim.jobId(),
                claim.fencingToken());
        return true;
    }

    private void terminal(Map<String, Object> row, String status, String code) {
        Timestamp now = now();
        jdbc.update(
                "UPDATE trace_runs SET status=?,verdict=NULL,reason_code=?,completed_at=? WHERE id=? AND status IN ('QUEUED','RUNNING')",
                status,
                code,
                now,
                row.get("run_id"));
        jdbc.update(
                "UPDATE processing_jobs SET status=?,last_error_code=?,finished_at=?,updated_at=?,lease_owner=NULL,lease_until=NULL WHERE id=? AND status NOT IN ('SUCCEEDED','FAILED','CANCELLED')",
                status,
                code,
                now,
                now,
                row.get("id"));
    }

    @Transactional
    public boolean expire(UUID id) {
        var row = lock(id);
        if (row == null
                || FINAL.contains(row.get("status"))
                || time(row, "deadline_at").isAfter(Instant.now(clock))) return false;
        terminal(row, "FAILED", "DEADLINE_EXCEEDED");
        return true;
    }

    @Transactional(readOnly = true)
    public List<UUID> recoverable() {
        return recoverable(null);
    }

    @Transactional(readOnly = true)
    public List<UUID> recoverable(String mode) {
        Timestamp now = now();
        return jdbc.query(
                """
                SELECT j.id FROM processing_jobs j JOIN trace_runs r ON r.id=j.run_id
                WHERE (?::text IS NULL OR r.execution_mode=?) AND j.status NOT IN ('SUCCEEDED','FAILED','CANCELLED') AND (
                deadline_at<=? OR (j.status='PENDING' AND updated_at<=?::timestamptz-interval '5 seconds')
                OR (j.status='QUEUED' AND last_published_at<=?::timestamptz-interval '60 seconds')
                OR (j.status='RETRY_WAIT' AND next_attempt_at<=?) OR (j.status='RUNNING' AND lease_until<=?))
                ORDER BY updated_at,j.id LIMIT 100
                """,
                (rs, n) -> rs.getObject(1, UUID.class),
                mode,
                mode,
                now,
                now,
                now,
                now,
                now);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int publicationAttempt(UUID id) {
        Timestamp now = now();
        var attempts =
                jdbc.query(
                        """
                UPDATE processing_jobs SET publish_attempts=publish_attempts+1,updated_at=? WHERE id=? AND deadline_at>? AND (
                status IN ('PENDING','QUEUED') OR (status='RETRY_WAIT' AND next_attempt_at<=?) OR (status='RUNNING' AND lease_until<=?)) RETURNING publish_attempts
                """,
                        (rs, n) -> rs.getInt(1),
                        now,
                        id,
                        now,
                        now,
                        now);
        return attempts.isEmpty() ? 0 : attempts.get(0);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void published(UUID id, int attempt, boolean success) {
        // A late confirm must not regress RUNNING/final states or bypass a retry delay.
        if (success)
            jdbc.update(
                    "UPDATE processing_jobs SET status='QUEUED',last_published_at=?,updated_at=?,last_error_code=NULL WHERE id=? AND publish_attempts=? AND status IN ('PENDING','QUEUED')",
                    now(),
                    now(),
                    id,
                    attempt);
        else
            jdbc.update(
                    "UPDATE processing_jobs SET last_error_code='PUBLISH_FAILED',updated_at=? WHERE id=? AND publish_attempts=? AND status IN ('PENDING','QUEUED')",
                    now(),
                    id,
                    attempt);
    }
}
