CREATE TABLE choice_analyses (
    id uuid PRIMARY KEY, run_id uuid NOT NULL REFERENCES trace_runs(id),
    choice_number smallint NOT NULL CHECK(choice_number BETWEEN 1 AND 5),
    original_text text NOT NULL CHECK(char_length(original_text) BETWEEN 1 AND 2000 AND btrim(original_text)<>''),
    relation varchar(24) NOT NULL CHECK(relation IN ('SUPPORTS','CONTRADICTS','INSUFFICIENT')),
    explanation text NOT NULL CHECK(char_length(explanation) BETWEEN 1 AND 10000 AND btrim(explanation)<>''),
    corrected_text text CHECK(corrected_text IS NULL OR (relation='CONTRADICTS' AND char_length(corrected_text) BETWEEN 1 AND 2000 AND btrim(corrected_text)<>'')),
    tags jsonb NOT NULL CHECK(jsonb_typeof(tags)='array' AND jsonb_array_length(tags)<=20),
    tag_set_version varchar(80) CHECK(tag_set_version IS NULL OR btrim(tag_set_version)<>''),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE(run_id,choice_number), UNIQUE(run_id,id),
    CHECK((jsonb_array_length(tags)=0)=(tag_set_version IS NULL))
);
CREATE TABLE choice_analysis_evidences (
    choice_analysis_id uuid NOT NULL, evidence_id uuid NOT NULL, run_id uuid NOT NULL,
    PRIMARY KEY(choice_analysis_id,evidence_id),
    FOREIGN KEY(run_id,choice_analysis_id) REFERENCES choice_analyses(run_id,id),
    FOREIGN KEY(run_id,evidence_id) REFERENCES evidence_results(run_id,id)
);
CREATE INDEX choice_analysis_evidences_run_idx ON choice_analysis_evidences(run_id);

CREATE FUNCTION guard_choice_write() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE r trace_runs%ROWTYPE; original text;
BEGIN
    IF TG_OP<>'INSERT' THEN RAISE EXCEPTION 'Choice analysis records are immutable' USING ERRCODE='23514'; END IF;
    SELECT * INTO r FROM trace_runs WHERE id=NEW.run_id FOR UPDATE;
    IF NOT FOUND OR r.execution_mode<>'LIVE' OR r.status<>'RUNNING' OR (r.checkpoint->>'VERIFY') IS DISTINCT FROM 'true' THEN
        RAISE EXCEPTION 'Choices require a verified running LIVE trace' USING ERRCODE='23514';
    END IF;
    IF TG_TABLE_NAME='choice_analyses' THEN
        SELECT value->>'text' INTO original FROM jsonb_array_elements(r.input_snapshot->'choices')
            WHERE (value->>'number')::int=NEW.choice_number;
        IF original IS DISTINCT FROM NEW.original_text THEN
            RAISE EXCEPTION 'Choice original must match frozen input' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER choice_analyses_guard BEFORE INSERT OR UPDATE OR DELETE ON choice_analyses FOR EACH ROW EXECUTE FUNCTION guard_choice_write();
CREATE TRIGGER choice_evidence_guard BEFORE INSERT OR UPDATE OR DELETE ON choice_analysis_evidences FOR EACH ROW EXECUTE FUNCTION guard_choice_write();

CREATE FUNCTION assert_complete_choices(selected uuid) RETURNS void LANGUAGE plpgsql AS $$
DECLARE count_choices int; r trace_runs%ROWTYPE;
BEGIN
    SELECT count(*) INTO count_choices FROM choice_analyses WHERE run_id=selected;
    IF count_choices=0 THEN RETURN; END IF;
    SELECT * INTO r FROM trace_runs WHERE id=selected;
    IF count_choices<>5 OR r.status<>'COMPLETED' OR r.execution_mode<>'LIVE'
        OR (r.checkpoint->>'VERIFY') IS DISTINCT FROM 'true' OR (r.checkpoint->>'PERSIST') IS DISTINCT FROM 'true'
        OR NOT EXISTS(SELECT 1 FROM processing_jobs WHERE run_id=selected AND status='SUCCEEDED')
        OR EXISTS(SELECT 1 FROM choice_analyses a WHERE a.run_id=selected AND a.relation<>'INSUFFICIENT'
            AND NOT EXISTS(SELECT 1 FROM choice_analysis_evidences e WHERE e.choice_analysis_id=a.id AND e.run_id=selected))
        OR EXISTS(SELECT 1 FROM choice_analyses a WHERE a.run_id=selected AND a.original_text IS DISTINCT FROM
            (SELECT value->>'text' FROM jsonb_array_elements(r.input_snapshot->'choices') WHERE (value->>'number')::int=a.choice_number)) THEN
        RAISE EXCEPTION 'Choice completion requires five frozen choices and atomic evidence/result success' USING ERRCODE='23514';
    END IF;
END; $$;
CREATE FUNCTION check_choice_completion() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_TABLE_NAME='trace_runs' THEN PERFORM assert_complete_choices(NEW.id);
    ELSE PERFORM assert_complete_choices(NEW.run_id); END IF;
    RETURN NULL;
END; $$;
CREATE CONSTRAINT TRIGGER choices_complete AFTER INSERT ON choice_analyses DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_choice_completion();
CREATE CONSTRAINT TRIGGER choice_links_complete AFTER INSERT ON choice_analysis_evidences DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_choice_completion();
CREATE CONSTRAINT TRIGGER trace_choices_complete AFTER UPDATE ON trace_runs DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_choice_completion();

CREATE FUNCTION guard_final_choice_trace() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status='COMPLETED' AND NEW IS DISTINCT FROM OLD AND EXISTS(SELECT 1 FROM choice_analyses WHERE run_id=OLD.id) THEN
        RAISE EXCEPTION 'Final trace with choices is immutable' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER trace_choices_immutable BEFORE UPDATE ON trace_runs FOR EACH ROW EXECUTE FUNCTION guard_final_choice_trace();
