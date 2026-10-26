package com.lawground.recommendation;

import com.lawground.auth.MemberResolver;
import com.lawground.global.config.JsonData;
import com.lawground.global.error.*;
import com.lawground.law.search.SearchValues;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** One DB representation for both embedding input and freshness checks. No network calls. */
@Service
public class QuestionEmbeddingStore {
    static final String INPUT =
            """
        jsonb_build_object('formatVersion','question-recommendation-v1',
          'questionType',q.question_type,'stem',q.stem,'choices',
          (SELECT jsonb_agg(jsonb_build_object('number',c.choice_number,'text',c.text) ORDER BY c.choice_number)
           FROM question_choices c WHERE c.question_id=q.id))::text
        """;
    static final String HASH = "encode(sha256(convert_to((" + INPUT + "),'UTF8')),'hex')";
    static final String PUBLIC =
            "(q.visibility='PUBLIC' AND q.source_kind='EXAM_IMPORT' AND q.answer_source='OFFICIAL_VERIFIED')";
    static final String UNIT =
            "(e.embedding IS NOT NULL AND abs(vector_norm(e.embedding)-1)<0.001)";

    public record Source(
            UUID id,
            long revision,
            UUID actor,
            String input,
            String hash,
            String model,
            boolean ready) {
        @Override
        public String toString() {
            return "QuestionEmbeddingSource[content omitted]";
        }
    }

    private final JdbcTemplate jdbc;
    private final MemberResolver members;
    private final Clock clock;

    public QuestionEmbeddingStore(JdbcTemplate jdbc, MemberResolver members, Clock clock) {
        this.jdbc = jdbc;
        this.members = members;
        this.clock = clock;
    }

    static ApiException conflict() {
        return new ApiException(
                HttpStatus.CONFLICT, ErrorCode.VERSION_CONFLICT, "문제 revision 또는 추천 색인이 변경되었습니다.");
    }

    @Transactional(readOnly = true)
    public Source load(UUID id, String model) {
        return read(id, model, false);
    }

    private Source read(UUID id, String model, boolean lock) {
        if (id == null || model == null || !model.matches("[0-9a-f]{40}"))
            throw new IllegalArgumentException("Question and pinned embedding commit required");
        UUID actor = members.requiredMemberId();
        if (!Boolean.TRUE.equals(
                jdbc.queryForObject(
                        "SELECT EXISTS(SELECT 1 FROM members WHERE id=? AND status='ACTIVE')",
                        Boolean.class,
                        actor)))
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCode.ACCESS_DENIED, "접근할 수 없습니다.");
        // Lock before taking the snapshot that reads embeddings, including concurrent inserts.
        if (lock)
            jdbc.queryForList(
                    "SELECT q.id FROM questions q WHERE q.id=? AND q.deleted_at IS NULL AND (q.owner_id=? OR "
                            + PUBLIC
                            + ") FOR UPDATE",
                    id,
                    actor);
        var rows =
                jdbc.queryForList(
                        "SELECT q.id,q.revision,"
                                + INPUT
                                + " AS input,e.id AS embedding_id,e.input_hash,e.status,"
                                + UNIT
                                + " AS unit FROM questions q LEFT JOIN question_embeddings e ON e.question_id=q.id AND e.question_revision=q.revision AND e.embedding_model_revision=? WHERE q.id=? AND q.deleted_at IS NULL AND (q.owner_id=? OR "
                                + PUBLIC
                                + ")",
                        model,
                        id,
                        actor);
        if (rows.isEmpty())
            throw new ApiException(
                    HttpStatus.NOT_FOUND, ErrorCode.RESOURCE_NOT_FOUND, "문제를 찾을 수 없습니다.");
        var row = rows.getFirst();
        String input = row.get("input").toString();
        String hash = JsonData.hash(input);
        boolean ready = "READY".equals(row.get("status"));
        if (ready && (!hash.equals(row.get("input_hash")) || !Boolean.TRUE.equals(row.get("unit"))))
            throw conflict();
        return new Source(
                id, ((Number) row.get("revision")).longValue(), actor, input, hash, model, ready);
    }

    @Transactional
    public boolean publish(Source expected, float[] vector) {
        String literal = SearchValues.vector(vector);
        double norm = 0;
        for (float n : vector) norm += (double) n * n;
        if (Math.abs(norm - 1) > 0.001) throw new IllegalArgumentException("Unit vector required");
        var current = read(expected.id(), expected.model(), true);
        if (!current.actor().equals(expected.actor())
                || current.revision() != expected.revision()
                || !current.hash().equals(expected.hash())
                || !current.input().equals(expected.input())) throw conflict();
        if (current.ready()) return false;
        jdbc.update(
                "INSERT INTO question_embeddings(id,question_id,question_revision,embedding_model_revision,input_hash,embedding,status,created_at,updated_at) VALUES (?,?,?,?,?,?::vector,'READY',?,?) ON CONFLICT(question_id,question_revision,embedding_model_revision) DO UPDATE SET input_hash=EXCLUDED.input_hash,embedding=EXCLUDED.embedding,status='READY',error_code=NULL,updated_at=EXCLUDED.updated_at",
                UUID.randomUUID(),
                current.id(),
                current.revision(),
                current.model(),
                current.hash(),
                literal,
                Timestamp.from(Instant.now(clock)),
                Timestamp.from(Instant.now(clock)));
        return true;
    }
}
