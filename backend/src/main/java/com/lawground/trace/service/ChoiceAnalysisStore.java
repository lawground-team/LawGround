package com.lawground.trace.service;

import com.lawground.global.config.JsonData;
import com.lawground.integration.ai.dto.VerifyDtos.*;
import com.lawground.trace.dto.TraceDtos;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Internal only. Public result access stays behind AnalysisService's ownership/scope checks. */
@Service
public class ChoiceAnalysisStore {
    private final JdbcTemplate jdbc;
    private final JsonData json;

    public ChoiceAnalysisStore(JdbcTemplate jdbc, JsonData json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void persist(
            UUID runId, Response response, Snapshot snapshot, List<TraceDtos.Evidence> stored) {
        if (response.choiceAnalyses() == null) return;
        var ids = new HashMap<String, UUID>();
        for (var e : response.evidences()) {
            var matching =
                    stored.stream()
                            .filter(
                                    s ->
                                            s.chunkId().equals(e.chunkId())
                                                    && s.start() == e.start()
                                                    && s.end() == e.end()
                                                    && s.quote().equals(e.quote()))
                            .toList();
            if (matching.size() != 1)
                throw new IllegalStateException("Evidence resolution mismatch");
            ids.put(e.evidenceKey(), matching.getFirst().evidenceId());
        }
        var originals = new HashMap<Integer, String>();
        snapshot.choices().forEach(c -> originals.put(c.number(), c.text()));
        for (var c : response.choiceAnalyses()) {
            UUID id = UUID.randomUUID();
            jdbc.update(
                    "INSERT INTO choice_analyses(id,run_id,choice_number,original_text,relation,explanation,corrected_text,tags,tag_set_version) VALUES (?,?,?,?,?,?,?,?::jsonb,?)",
                    id,
                    runId,
                    c.choiceNumber(),
                    originals.get(c.choiceNumber()),
                    c.relation().name(),
                    c.explanation(),
                    c.correctedText(),
                    json.write(c.tags()),
                    response.tagSetVersion());
            for (String key : c.evidenceKeys()) {
                UUID evidence = ids.get(key);
                if (evidence == null) throw new IllegalStateException("Unknown evidence key");
                jdbc.update(
                        "INSERT INTO choice_analysis_evidences(run_id,choice_analysis_id,evidence_id) VALUES (?,?,?)",
                        runId,
                        id,
                        evidence);
            }
        }
    }

    @Transactional(readOnly = true)
    public List<TraceDtos.ChoiceAnalysis> read(UUID runId) {
        var rows =
                jdbc.query(
                        "SELECT * FROM choice_analyses WHERE run_id=? ORDER BY choice_number",
                        (rs, n) -> {
                            if (!json.read(rs.getString("tags")).isEmpty()
                                    || rs.getString("tag_set_version") != null)
                                throw new IllegalStateException("Unsupported stored tag taxonomy");
                            var ids =
                                    jdbc.query(
                                            "SELECT evidence_id FROM choice_analysis_evidences WHERE run_id=? AND choice_analysis_id=? ORDER BY evidence_id",
                                            (r, i) -> r.getObject(1, UUID.class),
                                            runId,
                                            rs.getObject("id", UUID.class));
                            return new TraceDtos.ChoiceAnalysis(
                                    rs.getInt("choice_number"),
                                    rs.getString("original_text"),
                                    rs.getString("relation"),
                                    rs.getString("explanation"),
                                    rs.getString("corrected_text"),
                                    List.of(),
                                    ids);
                        },
                        runId);
        if (rows.isEmpty()) return null;
        if (rows.size() != 5) throw new IllegalStateException("Incomplete stored choices");
        return List.copyOf(rows);
    }
}
