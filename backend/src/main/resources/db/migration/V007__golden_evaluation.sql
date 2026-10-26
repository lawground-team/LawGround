CREATE TABLE golden_sets (
    id uuid PRIMARY KEY,
    name varchar(100) NOT NULL CHECK(btrim(name)<>''),
    version varchar(40) NOT NULL CHECK(btrim(version)<>''),
    split varchar(16) NOT NULL CHECK(split IN ('DEV','FINAL')),
    data_origin varchar(24) NOT NULL CHECK(data_origin IN ('SYNTHETIC','HUMAN_REVIEWED')),
    status varchar(16) NOT NULL DEFAULT 'DRAFT' CHECK(status IN ('DRAFT','FROZEN')),
    created_by uuid NOT NULL REFERENCES members(id),
    created_at timestamptz NOT NULL DEFAULT now(), frozen_at timestamptz,
    UNIQUE(name,version,split), CHECK((status='FROZEN')=(frozen_at IS NOT NULL))
);
CREATE TABLE golden_cases (
    id uuid PRIMARY KEY, set_id uuid NOT NULL REFERENCES golden_sets(id),
    question_id uuid NOT NULL REFERENCES questions(id), question_revision bigint NOT NULL CHECK(question_revision>=1),
    input_snapshot jsonb NOT NULL CHECK(jsonb_typeof(input_snapshot)='object'),
    input_hash char(64) NOT NULL CHECK(input_hash ~ '^[0-9a-f]{64}$'),
    leakage_group_key varchar(160) NOT NULL CHECK(btrim(leakage_group_key)<>''),
    expected_verdict varchar(16) NOT NULL CHECK(expected_verdict IN ('SUPPORTED','HOLD','REJECTED')),
    reviewer_id uuid NOT NULL REFERENCES members(id), reviewed_at timestamptz NOT NULL,
    source_note text NOT NULL CHECK(char_length(source_note) BETWEEN 1 AND 2000 AND btrim(source_note)<>''),
    created_at timestamptz NOT NULL DEFAULT now(), UNIQUE(set_id,input_hash), UNIQUE(set_id,id)
);
CREATE INDEX golden_cases_leakage_idx ON golden_cases(leakage_group_key);
CREATE TABLE golden_case_evidences (
    case_id uuid NOT NULL REFERENCES golden_cases(id), article_id uuid NOT NULL REFERENCES law_articles(id),
    review_note text NOT NULL CHECK(btrim(review_note)<>''), PRIMARY KEY(case_id,article_id)
);
CREATE TABLE evaluation_runs (
    id uuid PRIMARY KEY, set_id uuid NOT NULL REFERENCES golden_sets(id),
    requested_by uuid NOT NULL REFERENCES members(id),
    status varchar(16) NOT NULL CHECK(status IN ('PENDING','RUNNING','COMPLETED','FAILED')),
    execution_mode varchar(8) NOT NULL CHECK(execution_mode IN ('MOCK','LIVE')),
    config_snapshot jsonb NOT NULL CHECK(jsonb_typeof(config_snapshot)='object'),
    versions jsonb NOT NULL CHECK(jsonb_typeof(versions)='object'),
    metrics jsonb CHECK(metrics IS NULL OR jsonb_typeof(metrics)='object'),
    started_at timestamptz, completed_at timestamptz, created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE(id,set_id), CHECK((status IN ('COMPLETED','FAILED'))=(completed_at IS NOT NULL)),
    CHECK(status<>'COMPLETED' OR metrics IS NOT NULL)
);
CREATE INDEX evaluation_runs_set_status_idx ON evaluation_runs(set_id,status);

CREATE FUNCTION guard_evaluation_identity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.set_id IS DISTINCT FROM OLD.set_id OR NEW.requested_by IS DISTINCT FROM OLD.requested_by
        OR NEW.execution_mode IS DISTINCT FROM OLD.execution_mode OR NEW.config_snapshot IS DISTINCT FROM OLD.config_snapshot
        OR NEW.versions IS DISTINCT FROM OLD.versions THEN
        RAISE EXCEPTION 'Evaluation configuration is immutable' USING ERRCODE='23514';
    END IF;
    IF OLD.status IN ('COMPLETED','FAILED') AND NEW IS DISTINCT FROM OLD THEN
        RAISE EXCEPTION 'Final evaluation is immutable' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER evaluation_runs_immutable BEFORE UPDATE ON evaluation_runs FOR EACH ROW EXECUTE FUNCTION guard_evaluation_identity();

ALTER TABLE trace_runs DROP CONSTRAINT trace_runs_operation_check;
ALTER TABLE trace_runs ADD CONSTRAINT trace_runs_operation_kind_check CHECK(operation IN ('TRACE_CREATE','EVALUATION_TRACE_CREATE'));
ALTER TABLE trace_runs ADD COLUMN evaluation_run_id uuid REFERENCES evaluation_runs(id);
ALTER TABLE trace_runs ADD COLUMN evaluation_case_id uuid REFERENCES golden_cases(id);
ALTER TABLE trace_runs ADD CONSTRAINT trace_runs_evaluation_pair_check CHECK((evaluation_run_id IS NULL)=(evaluation_case_id IS NULL));
ALTER TABLE trace_runs ADD CONSTRAINT trace_runs_evaluation_scope_check CHECK(evaluation_run_id IS NULL OR (scope='EVALUATION' AND operation='EVALUATION_TRACE_CREATE'));
ALTER TABLE trace_runs ADD CONSTRAINT trace_runs_evaluation_operation_check CHECK(operation<>'EVALUATION_TRACE_CREATE' OR evaluation_run_id IS NOT NULL);
CREATE UNIQUE INDEX trace_runs_evaluation_case_unique ON trace_runs(evaluation_run_id,evaluation_case_id) WHERE evaluation_run_id IS NOT NULL;

CREATE TABLE evaluation_results (
    evaluation_run_id uuid NOT NULL, set_id uuid NOT NULL, case_id uuid NOT NULL,
    trace_run_id uuid UNIQUE REFERENCES trace_runs(id),
    status varchar(16) NOT NULL CHECK(status IN ('SUCCEEDED','FAILED')),
    retrieved_article_ids jsonb NOT NULL CHECK(jsonb_typeof(retrieved_article_ids)='array'),
    recall_at_5 double precision CHECK(recall_at_5>=0 AND recall_at_5<=1), hit_at_5 boolean,
    verdict_correct boolean NOT NULL, error_code varchar(80), latency_ms bigint NOT NULL CHECK(latency_ms>=0),
    actual_verdict varchar(16) CHECK(actual_verdict IN ('SUPPORTED','HOLD','REJECTED')),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY(evaluation_run_id,case_id),
    FOREIGN KEY(evaluation_run_id,set_id) REFERENCES evaluation_runs(id,set_id),
    FOREIGN KEY(set_id,case_id) REFERENCES golden_cases(set_id,id),
    CHECK(status<>'SUCCEEDED' OR trace_run_id IS NOT NULL),
    CHECK((status='FAILED')=(error_code IS NOT NULL)),
    CHECK(status<>'FAILED' OR verdict_correct=false),
    CHECK(status<>'SUCCEEDED' OR actual_verdict IS NOT NULL),
    CHECK((recall_at_5 IS NULL)=(hit_at_5 IS NULL))
);

CREATE FUNCTION guard_golden_case_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE selected uuid; frozen boolean;
BEGIN
    IF TG_TABLE_NAME='golden_cases' THEN
        IF TG_OP='UPDATE' AND OLD.set_id<>NEW.set_id THEN RAISE EXCEPTION 'Golden set movement forbidden' USING ERRCODE='23514'; END IF;
        IF TG_OP='DELETE' THEN selected=OLD.set_id; ELSE selected=NEW.set_id; END IF;
    ELSE
        IF TG_OP='UPDATE' AND OLD.case_id<>NEW.case_id THEN RAISE EXCEPTION 'Golden case movement forbidden' USING ERRCODE='23514'; END IF;
        IF TG_OP='DELETE' THEN SELECT set_id INTO selected FROM golden_cases WHERE id=OLD.case_id;
        ELSE SELECT set_id INTO selected FROM golden_cases WHERE id=NEW.case_id; END IF;
    END IF;
    SELECT status='FROZEN' INTO frozen FROM golden_sets WHERE id=selected FOR UPDATE;
    IF frozen THEN RAISE EXCEPTION 'Frozen golden labels are immutable' USING ERRCODE='23514'; END IF;
    IF TG_OP='DELETE' THEN RETURN OLD; ELSE RETURN NEW; END IF;
END; $$;
CREATE TRIGGER golden_cases_immutable BEFORE INSERT OR UPDATE OR DELETE ON golden_cases FOR EACH ROW EXECUTE FUNCTION guard_golden_case_mutation();
CREATE TRIGGER golden_evidences_immutable BEFORE INSERT OR UPDATE OR DELETE ON golden_case_evidences FOR EACH ROW EXECUTE FUNCTION guard_golden_case_mutation();

CREATE FUNCTION guard_golden_freeze() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status='FROZEN' THEN RAISE EXCEPTION 'Frozen golden set is immutable' USING ERRCODE='23514'; END IF;
    IF TG_OP='DELETE' THEN RETURN OLD; END IF;
    IF NEW.status='FROZEN' THEN
        PERFORM pg_advisory_xact_lock(hashtextextended('lawground:golden-split-freeze',0));
        IF NOT EXISTS(SELECT 1 FROM golden_cases WHERE set_id=NEW.id)
          OR EXISTS(SELECT 1 FROM golden_cases c JOIN members m ON m.id=c.reviewer_id WHERE c.set_id=NEW.id AND m.status<>'ACTIVE')
          OR EXISTS(SELECT 1 FROM golden_cases c WHERE c.set_id=NEW.id AND c.expected_verdict<>'HOLD' AND NOT EXISTS(SELECT 1 FROM golden_case_evidences e WHERE e.case_id=c.id)) THEN
            RAISE EXCEPTION 'Golden review incomplete' USING ERRCODE='23514';
        END IF;
        IF EXISTS(SELECT 1 FROM golden_cases a JOIN golden_cases b ON (a.leakage_group_key=b.leakage_group_key OR a.input_hash=b.input_hash)
            JOIN golden_sets s ON s.id=b.set_id WHERE a.set_id=NEW.id AND s.status='FROZEN' AND s.split<>NEW.split) THEN
            RAISE EXCEPTION 'DEV FINAL leakage detected' USING ERRCODE='23514';
        END IF;
        IF EXISTS(SELECT 1 FROM golden_cases c JOIN golden_case_evidences e ON e.case_id=c.id JOIN law_articles a ON a.id=e.article_id
            JOIN law_versions v ON v.id=a.law_version_id JOIN laws l ON l.id=v.law_id WHERE c.set_id=NEW.id AND
            (l.source_provider<>'LAW_GO_KR' OR v.applicability_status<>'VERIFIED' OR v.effective_from>(c.input_snapshot->>'referenceDate')::date OR
             (v.effective_to IS NOT NULL AND v.effective_to<=(c.input_snapshot->>'referenceDate')::date))) THEN
            RAISE EXCEPTION 'Golden evidence outside reviewed date corpus' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER golden_sets_freeze BEFORE UPDATE OR DELETE ON golden_sets FOR EACH ROW EXECUTE FUNCTION guard_golden_freeze();

CREATE FUNCTION validate_evaluation_trace() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.evaluation_run_id IS NOT NULL AND NOT EXISTS(
        SELECT 1 FROM evaluation_runs e JOIN golden_cases c ON c.set_id=e.set_id JOIN golden_sets s ON s.id=e.set_id
        WHERE e.id=NEW.evaluation_run_id AND c.id=NEW.evaluation_case_id AND e.requested_by=NEW.requester_id AND s.status='FROZEN'
        AND e.execution_mode=NEW.execution_mode AND c.question_id=NEW.question_id AND c.question_revision=NEW.question_revision
        AND c.input_hash=NEW.input_hash AND c.input_snapshot=NEW.input_snapshot AND NEW.reference_date=(c.input_snapshot->>'referenceDate')::date) THEN
        RAISE EXCEPTION 'Evaluation trace binding mismatch' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER trace_runs_evaluation_binding BEFORE INSERT OR UPDATE ON trace_runs FOR EACH ROW EXECUTE FUNCTION validate_evaluation_trace();

CREATE FUNCTION validate_evaluation_result() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.trace_run_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM trace_runs r WHERE r.id=NEW.trace_run_id
        AND r.evaluation_run_id=NEW.evaluation_run_id AND r.evaluation_case_id=NEW.case_id AND r.scope='EVALUATION') THEN
        RAISE EXCEPTION 'Evaluation result binding mismatch' USING ERRCODE='23514';
    END IF;
    IF NEW.status='SUCCEEDED' AND NOT EXISTS(SELECT 1 FROM trace_runs r JOIN processing_jobs j ON j.run_id=r.id
        WHERE r.id=NEW.trace_run_id AND r.status='COMPLETED' AND j.status='SUCCEEDED' AND r.verdict=NEW.actual_verdict AND r.execution_mode='LIVE') THEN
        RAISE EXCEPTION 'Successful evaluation needs completed LIVE trace' USING ERRCODE='23514';
    END IF;
    IF NEW.verdict_correct IS DISTINCT FROM (NEW.status='SUCCEEDED' AND EXISTS(SELECT 1 FROM golden_cases c WHERE c.id=NEW.case_id AND c.expected_verdict=NEW.actual_verdict)) THEN
        RAISE EXCEPTION 'Evaluation verdict metric mismatch' USING ERRCODE='23514';
    END IF;
    IF (NOT EXISTS(SELECT 1 FROM golden_case_evidences WHERE case_id=NEW.case_id)) IS DISTINCT FROM (NEW.recall_at_5 IS NULL) THEN
        RAISE EXCEPTION 'Evaluation search denominator mismatch' USING ERRCODE='23514';
    END IF;
    IF NEW.status='FAILED' AND NEW.recall_at_5 IS NOT NULL AND (NEW.recall_at_5<>0 OR NEW.hit_at_5<>false) THEN
        RAISE EXCEPTION 'Technical failure cannot earn retrieval credit' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER evaluation_results_binding BEFORE INSERT OR UPDATE ON evaluation_results FOR EACH ROW EXECUTE FUNCTION validate_evaluation_result();
