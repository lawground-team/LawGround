CREATE TABLE batch_runs (
    id uuid PRIMARY KEY, requested_by uuid NOT NULL REFERENCES members(id),
    request_hash char(64) NOT NULL CHECK(request_hash ~ '^[0-9a-f]{64}$'),
    status varchar(16) NOT NULL CHECK(status IN ('RUNNING','COMPLETED')),
    config_snapshot jsonb NOT NULL CHECK(jsonb_typeof(config_snapshot)='object'),
    aggregate jsonb CHECK(aggregate IS NULL OR jsonb_typeof(aggregate)='object'),
    created_at timestamptz NOT NULL DEFAULT now(), completed_at timestamptz,
    CHECK((status='COMPLETED')=(completed_at IS NOT NULL)),
    CHECK((status='COMPLETED')=(aggregate IS NOT NULL))
);
CREATE TABLE batch_items (
    batch_run_id uuid NOT NULL REFERENCES batch_runs(id), question_id uuid NOT NULL REFERENCES questions(id),
    question_revision bigint NOT NULL CHECK(question_revision>=1),
    input_snapshot jsonb NOT NULL CHECK(jsonb_typeof(input_snapshot)='object'),
    input_hash char(64) NOT NULL CHECK(input_hash ~ '^[0-9a-f]{64}$'),
    status varchar(16) NOT NULL CHECK(status IN ('PENDING','SUCCEEDED','FAILED')),
    trace_run_id uuid UNIQUE REFERENCES trace_runs(id),
    verdict varchar(16) CHECK(verdict IN ('SUPPORTED','HOLD','REJECTED')),
    error_code varchar(80), PRIMARY KEY(batch_run_id,question_id),
    CHECK((status='FAILED')=(error_code IS NOT NULL)),
    CHECK((status='SUCCEEDED')=(verdict IS NOT NULL)),
    CHECK(status<>'SUCCEEDED' OR trace_run_id IS NOT NULL)
);
ALTER TABLE trace_runs DROP CONSTRAINT trace_runs_operation_kind_check;
ALTER TABLE trace_runs ADD CONSTRAINT trace_runs_operation_kind_check CHECK(operation IN ('TRACE_CREATE','EVALUATION_TRACE_CREATE','BATCH_TRACE_CREATE'));
ALTER TABLE trace_runs ADD COLUMN batch_run_id uuid;
ALTER TABLE trace_runs ADD CONSTRAINT trace_runs_batch_item_fk FOREIGN KEY(batch_run_id,question_id) REFERENCES batch_items(batch_run_id,question_id);
ALTER TABLE trace_runs ADD CONSTRAINT trace_runs_batch_scope_check CHECK(
    (batch_run_id IS NULL AND operation<>'BATCH_TRACE_CREATE' AND scope<>'BATCH') OR
    (batch_run_id IS NOT NULL AND scope='BATCH' AND operation='BATCH_TRACE_CREATE' AND execution_mode='LIVE' AND evaluation_run_id IS NULL));
CREATE UNIQUE INDEX trace_runs_batch_question_unique ON trace_runs(batch_run_id,question_id) WHERE batch_run_id IS NOT NULL;
CREATE INDEX batch_runs_owner_idx ON batch_runs(requested_by,created_at DESC);

CREATE FUNCTION guard_batch_run() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Batch audit is immutable' USING ERRCODE='23514'; END IF;
    IF OLD.status='COMPLETED' OR NEW.id<>OLD.id OR NEW.requested_by<>OLD.requested_by OR
        NEW.request_hash<>OLD.request_hash OR NEW.config_snapshot<>OLD.config_snapshot OR NEW.created_at<>OLD.created_at THEN
        RAISE EXCEPTION 'Batch configuration is immutable' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER batch_run_guard BEFORE UPDATE OR DELETE ON batch_runs FOR EACH ROW EXECUTE FUNCTION guard_batch_run();
CREATE FUNCTION guard_batch_item() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Batch audit is immutable' USING ERRCODE='23514'; END IF;
    IF TG_OP='UPDATE' AND (OLD.status<>'PENDING' OR NEW.batch_run_id<>OLD.batch_run_id OR NEW.question_id<>OLD.question_id OR
        NEW.question_revision<>OLD.question_revision OR NEW.input_snapshot<>OLD.input_snapshot OR NEW.input_hash<>OLD.input_hash OR
        (OLD.trace_run_id IS NOT NULL AND NEW.trace_run_id IS DISTINCT FROM OLD.trace_run_id)) THEN
        RAISE EXCEPTION 'Batch item identity or terminal result is immutable' USING ERRCODE='23514';
    END IF;
    IF NEW.trace_run_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM trace_runs r WHERE r.id=NEW.trace_run_id AND
        r.batch_run_id=NEW.batch_run_id AND r.question_id=NEW.question_id) THEN
        RAISE EXCEPTION 'Batch item trace mismatch' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER batch_item_guard BEFORE INSERT OR UPDATE OR DELETE ON batch_items FOR EACH ROW EXECUTE FUNCTION guard_batch_item();
CREATE FUNCTION validate_batch_trace() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='UPDATE' AND NEW.batch_run_id IS DISTINCT FROM OLD.batch_run_id THEN
        RAISE EXCEPTION 'Batch binding is immutable' USING ERRCODE='23514';
    END IF;
    IF NEW.batch_run_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM batch_runs b JOIN batch_items i ON i.batch_run_id=b.id
        WHERE b.id=NEW.batch_run_id AND b.requested_by=NEW.requester_id AND i.question_id=NEW.question_id AND
        i.question_revision=NEW.question_revision AND i.input_hash=NEW.input_hash AND i.input_snapshot=NEW.input_snapshot AND
        NEW.reference_date=(i.input_snapshot->>'referenceDate')::date) THEN
        RAISE EXCEPTION 'Batch frozen trace mismatch' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER batch_trace_binding BEFORE INSERT OR UPDATE ON trace_runs FOR EACH ROW EXECUTE FUNCTION validate_batch_trace();
