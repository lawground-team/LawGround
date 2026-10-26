package com.lawground.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.lawground.global.config.JsonData;
import com.lawground.global.error.*;
import com.lawground.question.service.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Restricted internal curation service. No public management endpoint or label-to-model path. */
@Service
public class GoldenSetService {
    public enum Split {
        DEV,
        FINAL
    }

    public enum Origin {
        SYNTHETIC,
        HUMAN_REVIEWED
    }

    public enum Verdict {
        SUPPORTED,
        HOLD,
        REJECTED
    }

    public record Dataset(
            UUID id,
            String name,
            String version,
            Split split,
            Origin origin,
            String status,
            UUID createdBy) {}

    public record Case(
            UUID id,
            UUID questionId,
            long revision,
            JsonNode snapshot,
            String inputHash,
            String leakageGroup,
            Verdict expectedVerdict,
            Set<UUID> expectedArticles,
            String sourceNote) {
        @Override
        public String toString() {
            return "GoldenCase[labels and content omitted]";
        }
    }

    private final JdbcTemplate jdbc;
    private final JsonData json;
    private final EvaluationAccess access;
    private final QuestionService questions;
    private final Clock clock;

    public GoldenSetService(
            JdbcTemplate jdbc,
            JsonData json,
            EvaluationAccess access,
            QuestionService questions,
            Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.access = access;
        this.questions = questions;
        this.clock = clock;
    }

    @Transactional
    public Dataset create(String name, String version, Split split, Origin origin) {
        var actor = access.requiredManager();
        text(name, 100);
        text(version, 40);
        if (split == null || origin == null) throw invalid();
        UUID id = UUID.randomUUID();
        try {
            jdbc.update(
                    "INSERT INTO golden_sets(id,name,version,split,data_origin,created_by) VALUES (?,?,?,?,?,?)",
                    id,
                    name,
                    version,
                    split.name(),
                    origin.name(),
                    actor);
        } catch (DataIntegrityViolationException duplicate) {
            throw conflict("같은 이름·버전·분할의 평가셋이 있습니다.");
        }
        return owned(id, false);
    }

    @Transactional(readOnly = true)
    public Dataset get(UUID id) {
        return owned(id, false);
    }

    private Dataset owned(UUID id, boolean lock) {
        UUID actor = access.requiredManager();
        var rows =
                jdbc.query(
                        "SELECT * FROM golden_sets WHERE id=? AND created_by=?"
                                + (lock ? " FOR UPDATE" : ""),
                        (rs, n) ->
                                new Dataset(
                                        rs.getObject("id", UUID.class),
                                        rs.getString("name"),
                                        rs.getString("version"),
                                        Split.valueOf(rs.getString("split")),
                                        Origin.valueOf(rs.getString("data_origin")),
                                        rs.getString("status"),
                                        rs.getObject("created_by", UUID.class)),
                        id,
                        actor);
        if (rows.isEmpty()) throw missing();
        return rows.getFirst();
    }

    @Transactional
    public Case addCase(
            UUID setId,
            UUID questionId,
            long revision,
            String group,
            Verdict verdict,
            Set<UUID> articleIds,
            String note) {
        UUID actor = access.requiredManager();
        var set = owned(setId, true);
        if (!set.status().equals("DRAFT")) throw conflict("고정된 평가셋은 수정할 수 없습니다.");
        text(group, 160);
        text(note, 2000);
        if (verdict == null
                || articleIds == null
                || articleIds.size() > 20
                || articleIds.stream().anyMatch(Objects::isNull)) throw invalid();
        if (jdbc.queryForObject(
                        "SELECT count(*) FROM golden_cases WHERE set_id=?", Integer.class, setId)
                >= 100) throw conflict("평가셋의 초기 크기 상한을 넘었습니다.");
        var q = questions.get(questionId);
        if (q.revision() != revision) throw conflict("문제 revision이 변경되었습니다.");
        for (var article : articleIds) {
            Boolean reviewed =
                    jdbc.queryForObject(
                            "SELECT EXISTS(SELECT 1 FROM law_articles a JOIN law_versions v ON v.id=a.law_version_id JOIN laws l ON l.id=v.law_id WHERE a.id=? AND l.source_provider='LAW_GO_KR' AND v.applicability_status='VERIFIED' AND v.effective_from<=? AND (v.effective_to IS NULL OR v.effective_to>?))",
                            Boolean.class,
                            article,
                            q.referenceDate(),
                            q.referenceDate());
            if (!Boolean.TRUE.equals(reviewed)) throw invalid();
        }
        String snapshot = json.write(QuestionSnapshots.from(q));
        UUID id = UUID.randomUUID();
        try {
            jdbc.update(
                    "INSERT INTO golden_cases(id,set_id,question_id,question_revision,input_snapshot,input_hash,leakage_group_key,expected_verdict,reviewer_id,reviewed_at,source_note) VALUES (?,?,?,?,?::jsonb,?,?,?,?,?,?)",
                    id,
                    setId,
                    questionId,
                    revision,
                    snapshot,
                    JsonData.hash(snapshot),
                    group,
                    verdict.name(),
                    actor,
                    Timestamp.from(Instant.now(clock)),
                    note);
            for (var article : articleIds)
                jdbc.update(
                        "INSERT INTO golden_case_evidences(case_id,article_id,review_note) VALUES (?,?,?)",
                        id,
                        article,
                        note);
        } catch (DataIntegrityViolationException duplicate) {
            throw conflict("중복된 문제이거나 수정할 수 없는 평가셋입니다.");
        }
        return casesInternal(setId).stream()
                .filter(c -> c.id().equals(id))
                .findFirst()
                .orElseThrow();
    }

    @Transactional
    public Dataset freeze(UUID id) {
        // Same global serialization key as the DB trigger; prevents DEV/FINAL write skew.
        access.requiredManager();
        jdbc.queryForObject(
                "SELECT pg_advisory_xact_lock(hashtextextended('lawground:golden-split-freeze',0))",
                Object.class);
        var set = owned(id, true);
        if (set.status().equals("FROZEN")) return set;
        try {
            jdbc.update(
                    "UPDATE golden_sets SET status='FROZEN',frozen_at=? WHERE id=?",
                    Timestamp.from(Instant.now(clock)),
                    id);
        } catch (DataIntegrityViolationException invalid) {
            throw conflict("검수 미완료 또는 DEV/FINAL 중복 문제를 확인해 주세요.");
        }
        return owned(id, false);
    }

    @Transactional
    public void removeCase(UUID setId, UUID caseId) {
        var set = owned(setId, true);
        if (!"DRAFT".equals(set.status())) throw conflict("고정된 평가셋은 수정할 수 없습니다.");
        if (!Boolean.TRUE.equals(
                jdbc.queryForObject(
                        "SELECT EXISTS(SELECT 1 FROM golden_cases WHERE id=? AND set_id=?)",
                        Boolean.class,
                        caseId,
                        setId))) throw missing();
        jdbc.update("DELETE FROM golden_case_evidences WHERE case_id=?", caseId);
        jdbc.update("DELETE FROM golden_cases WHERE id=? AND set_id=?", caseId, setId);
    }

    @Transactional(readOnly = true)
    public List<Case> cases(UUID id) {
        owned(id, false);
        return casesInternal(id);
    }

    private List<Case> casesInternal(UUID id) {
        return jdbc.query(
                "SELECT * FROM golden_cases WHERE set_id=? ORDER BY created_at,id",
                (rs, n) -> {
                    UUID caseId = rs.getObject("id", UUID.class);
                    var articles =
                            new HashSet<>(
                                    jdbc.query(
                                            "SELECT article_id FROM golden_case_evidences WHERE case_id=? ORDER BY article_id",
                                            (a, i) -> a.getObject(1, UUID.class),
                                            caseId));
                    return new Case(
                            caseId,
                            rs.getObject("question_id", UUID.class),
                            rs.getLong("question_revision"),
                            json.read(rs.getString("input_snapshot")),
                            rs.getString("input_hash"),
                            rs.getString("leakage_group_key"),
                            Verdict.valueOf(rs.getString("expected_verdict")),
                            Set.copyOf(articles),
                            rs.getString("source_note"));
                },
                id);
    }

    static void text(String value, int max) {
        if (value == null
                || value.isBlank()
                || value.indexOf('\0') >= 0
                || value.codePointCount(0, value.length()) > max) throw invalid();
    }

    static ApiException invalid() {
        return new ApiException(
                HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED, "평가 입력과 검수 근거를 확인해 주세요.");
    }

    static ApiException missing() {
        return new ApiException(
                HttpStatus.NOT_FOUND, ErrorCode.RESOURCE_NOT_FOUND, "평가 자료를 찾을 수 없습니다.");
    }

    static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, ErrorCode.VERSION_CONFLICT, message);
    }
}
