package com.lawground.question.service;

import com.lawground.auth.MemberResolver;
import com.lawground.global.error.ApiException;
import com.lawground.global.error.ErrorCode;
import com.lawground.global.web.ApiParameters;
import com.lawground.global.web.PageCursor;
import com.lawground.global.web.PageResponse;
import com.lawground.question.dto.CreateQuestionRequest;
import com.lawground.question.dto.QuestionListItem;
import com.lawground.question.dto.QuestionResponse;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class QuestionLibraryService {
    private final JdbcTemplate jdbc;
    private final MemberResolver members;
    private final QuestionService questions;
    private final PageCursor cursors;
    private final Clock clock;

    public QuestionLibraryService(
            JdbcTemplate jdbc,
            MemberResolver members,
            QuestionService questions,
            PageCursor cursors,
            Clock clock) {
        this.jdbc = jdbc;
        this.members = members;
        this.questions = questions;
        this.cursors = cursors;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageResponse<QuestionListItem> list(String scope, int limit, String cursor) {
        if (!List.of("mine", "public").contains(scope)) throw ApiParameters.invalid();
        UUID member =
                scope.equals("mine") ? members.requiredMemberId() : members.optionalMemberId();
        String binding = "questions:" + scope + ":" + member;
        var position = cursors.decode(cursor, binding);
        var args = new ArrayList<Object>();
        args.add(member);
        String where = scope.equals("mine") ? "q.owner_id=?" : "q.visibility='PUBLIC'";
        if (scope.equals("mine")) args.add(member);
        if (position != null) {
            where += " AND (q.created_at,q.id)<(?,?)";
            args.add(Timestamp.from(position.createdAt()));
            args.add(position.id());
        }
        args.add(limit + 1);
        var rows =
                jdbc.query(
                        """
                SELECT q.*,r.id AS run_id,r.status AS run_status,r.verdict,r.execution_mode
                FROM questions q LEFT JOIN LATERAL (
                  SELECT id,status,verdict,execution_mode FROM trace_runs
                  WHERE question_id=q.id AND requester_id=? AND scope='USER'
                  ORDER BY created_at DESC,id DESC LIMIT 1
                ) r ON true WHERE q.deleted_at IS NULL AND """
                                + " "
                                + where
                                + " ORDER BY q.created_at DESC,q.id DESC LIMIT ?",
                        (rs, n) -> {
                            String stem = rs.getString("stem");
                            String preview =
                                    stem.substring(
                                            0,
                                            stem.offsetByCodePoints(
                                                    0,
                                                    Math.min(
                                                            120,
                                                            stem.codePointCount(
                                                                    0, stem.length()))));
                            UUID run = rs.getObject("run_id", UUID.class);
                            return new QuestionListItem(
                                    rs.getObject("id", UUID.class),
                                    preview,
                                    rs.getString("subject"),
                                    rs.getDate("reference_date").toLocalDate(),
                                    rs.getString("visibility"),
                                    rs.getLong("revision"),
                                    rs.getTimestamp("created_at").toInstant(),
                                    run == null
                                            ? null
                                            : new QuestionListItem.RunSummary(
                                                    run,
                                                    rs.getString("run_status"),
                                                    rs.getString("verdict"),
                                                    rs.getString("execution_mode")));
                        },
                        args.toArray());
        var items = List.copyOf(rows.subList(0, Math.min(limit, rows.size())));
        var last = items.isEmpty() ? null : items.get(items.size() - 1);
        return new PageResponse<>(
                items,
                rows.size() > limit ? cursors.encode(binding, last.createdAt(), last.id()) : null);
    }

    @Transactional
    public QuestionResponse update(UUID id, long revision, CreateQuestionRequest input) {
        UUID owner = members.requiredMemberId();
        lockOwned(id, owner, revision);
        if (input.choices().stream().map(CreateQuestionRequest.Choice::number).distinct().count()
                != 5) throw ApiParameters.invalid();
        String hash = QuestionService.contentHash(input);
        if (Boolean.TRUE.equals(
                jdbc.queryForObject(
                        "SELECT EXISTS(SELECT 1 FROM questions WHERE owner_id=? AND content_hash=? AND deleted_at IS NULL AND id<>?)",
                        Boolean.class,
                        owner,
                        hash,
                        id)))
            throw new ApiException(
                    HttpStatus.CONFLICT, ErrorCode.DUPLICATE_QUESTION, "이미 등록한 문제입니다.");
        jdbc.update(
                "UPDATE questions SET subject=?,stem=?,question_type=?,provided_answer_number=?,reference_date=?,content_hash=?,visibility='PRIVATE',source_kind='USER_INPUT',answer_source='USER_PROVIDED',source_key=NULL,source_url=NULL,source_metadata=NULL,revision=revision+1,updated_at=? WHERE id=? AND revision=?",
                input.subject().name(),
                input.stem(),
                input.questionType().name(),
                input.providedAnswerNumber(),
                input.referenceDate(),
                hash,
                Timestamp.from(Instant.now(clock)),
                id,
                revision);
        for (var choice : input.choices())
            jdbc.update(
                    "UPDATE question_choices SET text=? WHERE question_id=? AND choice_number=?",
                    choice.text(),
                    id,
                    choice.number());
        return questions.get(id);
    }

    @Transactional
    public void delete(UUID id, long revision) {
        lockOwned(id, members.requiredMemberId(), revision);
        Timestamp now = Timestamp.from(Instant.now(clock));
        jdbc.update(
                "UPDATE questions SET deleted_at=?,updated_at=?,revision=revision+1 WHERE id=? AND revision=?",
                now,
                now,
                id,
                revision);
        jdbc.update(
                "UPDATE trace_runs SET status='CANCELLED',cancel_requested_at=?,completed_at=? WHERE question_id=? AND status IN ('QUEUED','RUNNING')",
                now,
                now,
                id);
        jdbc.update(
                "UPDATE processing_jobs j SET status='CANCELLED',finished_at=?,updated_at=?,lease_owner=NULL,lease_until=NULL WHERE run_id IN (SELECT id FROM trace_runs WHERE question_id=? AND status='CANCELLED') AND j.status NOT IN ('SUCCEEDED','FAILED','CANCELLED')",
                now,
                now,
                id);
    }

    private void lockOwned(UUID id, UUID owner, long expected) {
        var revisions =
                jdbc.query(
                        "SELECT revision FROM questions WHERE id=? AND owner_id=? AND deleted_at IS NULL FOR UPDATE",
                        (rs, n) -> rs.getLong(1),
                        id,
                        owner);
        if (revisions.isEmpty())
            throw new ApiException(
                    HttpStatus.NOT_FOUND, ErrorCode.RESOURCE_NOT_FOUND, "문제를 찾을 수 없습니다.");
        if (revisions.get(0) != expected)
            throw new ApiException(
                    HttpStatus.CONFLICT, ErrorCode.VERSION_CONFLICT, "문제가 변경되었습니다. 다시 조회해 주세요.");
    }
}
