package com.lawground.law.search;

import java.util.List;
import java.util.UUID;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Only hand-authored fixture vectors/tokens in an isolated test corpus. No LIVE adapter yet. */
@Service
public class FixtureIndexWriter {
    private final JdbcTemplate jdbc;
    private final boolean allowed;

    public FixtureIndexWriter(JdbcTemplate jdbc, Environment env) {
        this.jdbc = jdbc;
        this.allowed =
                List.of(env.getActiveProfiles()).contains("test")
                        && !List.of(env.getActiveProfiles()).contains("prod");
    }

    @Transactional
    public void write(
            UUID chunkId, String model, String tokenizer, float[] vector, List<String> tokens) {
        if (!allowed) throw new IllegalStateException("Synthetic vectors are isolated-test only");
        SearchValues.revision(model);
        SearchValues.revision(tokenizer);
        if (!model.startsWith("fixture-") || !tokenizer.startsWith("fixture-"))
            throw new IllegalArgumentException("Explicit fixture revisions required");
        String embedding = SearchValues.vector(vector), joined = SearchValues.tokens(tokens);
        var hashes =
                jdbc.query(
                        "SELECT c.content_hash FROM law_chunks c JOIN law_articles a ON a.id=c.article_id JOIN law_versions v ON v.id=a.law_version_id JOIN laws l ON l.id=v.law_id WHERE c.id=? AND l.source_provider='FIXTURE'",
                        (rs, n) -> rs.getString(1),
                        chunkId);
        if (hashes.isEmpty()) throw new IllegalArgumentException("Fixture corpus required");
        jdbc.update(
                connection -> {
                    var statement =
                            connection.prepareStatement(
                                    """
                    INSERT INTO law_chunk_indexes(id,chunk_id,embedding_model_revision,tokenizer_version,source_hash,embedding,dense_status,tokens,search_vector,lexical_status)
                    VALUES (?,?,?,?,?,?::vector,'READY',?,to_tsvector('simple',?),'READY')
                    ON CONFLICT(chunk_id,embedding_model_revision,tokenizer_version,source_hash) DO UPDATE SET embedding=EXCLUDED.embedding,tokens=EXCLUDED.tokens,search_vector=EXCLUDED.search_vector,dense_status='READY',lexical_status='READY',error_code=NULL,updated_at=now()
                    """);
                    statement.setObject(1, UUID.randomUUID());
                    statement.setObject(2, chunkId);
                    statement.setString(3, model);
                    statement.setString(4, tokenizer);
                    statement.setString(5, hashes.get(0));
                    statement.setString(6, embedding);
                    statement.setArray(
                            7, connection.createArrayOf("text", tokens.toArray(String[]::new)));
                    statement.setString(8, joined);
                    return statement;
                });
    }
}
