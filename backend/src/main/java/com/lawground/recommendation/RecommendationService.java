package com.lawground.recommendation;

import com.lawground.auth.MemberResolver;
import com.lawground.global.error.*;
import com.lawground.global.web.PageResponse;
import com.lawground.job.service.LiveRunPlan;
import com.lawground.question.service.QuestionService;
import com.lawground.trace.service.AnalysisService;
import java.time.LocalDate;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class RecommendationService {
    public static final double MIN_SIMILARITY = 0.65;

    public record Item(
            UUID questionId, String reason, List<UUID> sharedArticleIds, Double similarity) {}

    private record Basis(
            UUID article, LocalDate from, LocalDate to, String provider, String status) {}

    private final JdbcTemplate jdbc;
    private final MemberResolver members;
    private final AnalysisService analysis;
    private final QuestionService questions;

    public RecommendationService(
            JdbcTemplate jdbc,
            MemberResolver members,
            AnalysisService analysis,
            QuestionService questions) {
        this.jdbc = jdbc;
        this.members = members;
        this.analysis = analysis;
        this.questions = questions;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResponse<Item> list(UUID runId, int limit) {
        if (limit < 1 || limit > 10) throw com.lawground.global.web.ApiParameters.invalid();
        UUID actor = members.requiredMemberId();
        var run = analysis.get(runId);
        if (!"COMPLETED".equals(run.status()))
            throw new ApiException(
                    HttpStatus.CONFLICT, ErrorCode.RESULT_NOT_READY, "분석 결과가 아직 준비되지 않았습니다.");
        var question = questions.get(run.questionId());
        if (question.revision() != run.questionRevision()) throw QuestionEmbeddingStore.conflict();
        if (!"LIVE".equals(run.executionMode())
                || !"SUPPORTED".equals(run.verdict())
                || !LiveRunPlan.VERIFIED_PIPELINE.equals(
                        run.versions().path("pipelineVersion").asText())) return empty();
        String model = run.versions().path("embeddingModelRevision").asText();
        if (!model.matches("[0-9a-f]{40}")) return empty();
        var basis =
                jdbc.query(
                        "SELECT a.id,v.effective_from,v.effective_to,l.source_provider,v.applicability_status FROM evidence_results e LEFT JOIN law_chunks c ON c.id=e.chunk_id LEFT JOIN law_articles a ON a.id=c.article_id LEFT JOIN law_versions v ON v.id=a.law_version_id LEFT JOIN laws l ON l.id=v.law_id WHERE e.run_id=?",
                        (rs, n) ->
                                new Basis(
                                        rs.getObject(1, UUID.class),
                                        rs.getObject(2, LocalDate.class),
                                        rs.getObject(3, LocalDate.class),
                                        rs.getString(4),
                                        rs.getString(5)),
                        runId);
        if (basis.isEmpty()
                || basis.stream()
                        .anyMatch(
                                b ->
                                        b.article() == null
                                                || !"LAW_GO_KR".equals(b.provider())
                                                || !"VERIFIED".equals(b.status())
                                                || b.from().isAfter(run.referenceDate())
                                                || b.to() != null
                                                        && !b.to().isAfter(run.referenceDate())))
            return empty();
        // SQL uses caller-owned USER traces only, including for public candidate questions.
        // No other member's private analysis/evidence is a recommendation channel.
        String sql =
                """
            WITH source_vector AS (
              SELECT e.embedding FROM questions q JOIN question_embeddings e ON e.question_id=q.id
              WHERE q.id=? AND e.question_revision=q.revision AND e.embedding_model_revision=?
                AND e.status='READY' AND e.input_hash=%s AND %s
            ), basis AS (
              SELECT DISTINCT a.id,a.law_version_id FROM evidence_results er
              JOIN law_chunks c ON c.id=er.chunk_id JOIN law_articles a ON a.id=c.article_id WHERE er.run_id=?
            ), eligible AS (
              SELECT q.* FROM questions q WHERE q.id<>? AND q.deleted_at IS NULL AND q.subject=?
              AND ((q.owner_id=? AND EXISTS(SELECT 1 FROM trace_runs r WHERE r.question_id=q.id
                 AND r.question_revision=q.revision AND r.requester_id=? AND r.scope='USER'
                 AND r.status='COMPLETED' AND r.execution_mode='LIVE' AND r.verdict='SUPPORTED'
                 AND r.pipeline_version=?)) OR %s)
              AND NOT EXISTS(SELECT 1 FROM basis b JOIN law_versions v ON v.id=b.law_version_id
                 WHERE q.reference_date<v.effective_from OR (v.effective_to IS NOT NULL AND q.reference_date>=v.effective_to))
            ), scored AS (
              SELECT q.id,
                ARRAY(SELECT DISTINCT b.id FROM trace_runs r JOIN evidence_results er ON er.run_id=r.id
                  JOIN law_chunks c ON c.id=er.chunk_id JOIN basis b ON b.id=c.article_id
                  WHERE r.question_id=q.id AND r.question_revision=q.revision AND r.requester_id=?
                    AND r.scope='USER' AND r.status='COMPLETED' AND r.execution_mode='LIVE'
                    AND r.verdict='SUPPORTED' AND r.pipeline_version=? ORDER BY b.id) AS shared,
                CASE WHEN e.status='READY' AND e.input_hash=%s AND %s AND EXISTS(SELECT 1 FROM source_vector)
                  THEN GREATEST(-1.0,LEAST(1.0,1-(e.embedding <=> (SELECT embedding FROM source_vector))))
                  ELSE NULL END AS similarity
              FROM eligible q LEFT JOIN question_embeddings e ON e.question_id=q.id
                AND e.question_revision=q.revision AND e.embedding_model_revision=?
            ) SELECT * FROM scored WHERE cardinality(shared)>0 OR similarity>=?
              ORDER BY (cardinality(shared)>0) DESC,cardinality(shared) DESC,similarity DESC NULLS LAST,id ASC LIMIT ?
            """
                        .formatted(
                                QuestionEmbeddingStore.HASH,
                                QuestionEmbeddingStore.UNIT,
                                QuestionEmbeddingStore.PUBLIC,
                                QuestionEmbeddingStore.HASH,
                                QuestionEmbeddingStore.UNIT);
        var items =
                jdbc.query(
                        sql,
                        (rs, n) -> {
                            var shared = (UUID[]) rs.getArray("shared").getArray();
                            Double similarity = (Double) rs.getObject("similarity");
                            return new Item(
                                    rs.getObject("id", UUID.class),
                                    shared.length > 0
                                            ? "같은 근거 조문을 다룬 문제입니다."
                                            : "같은 모델의 문제 임베딩이 유사한 문제입니다.",
                                    Arrays.stream(shared).limit(20).toList(),
                                    similarity);
                        },
                        run.questionId(),
                        model,
                        runId,
                        run.questionId(),
                        question.subject(),
                        actor,
                        actor,
                        LiveRunPlan.VERIFIED_PIPELINE,
                        actor,
                        LiveRunPlan.VERIFIED_PIPELINE,
                        model,
                        MIN_SIMILARITY,
                        limit);
        return new PageResponse<>(items, null);
    }

    private PageResponse<Item> empty() {
        return new PageResponse<>(List.of(), null);
    }
}
