CREATE TABLE trace_runs (
    id uuid PRIMARY KEY,
    question_id uuid NOT NULL REFERENCES questions(id),
    requester_id uuid NOT NULL REFERENCES members(id),
    question_revision bigint NOT NULL CHECK(question_revision>=1),
    input_snapshot jsonb NOT NULL CHECK(jsonb_typeof(input_snapshot)='object'),
    input_hash char(64) NOT NULL CHECK(input_hash ~ '^[0-9a-f]{64}$'),
    reference_date date NOT NULL,
    operation varchar(32) NOT NULL CHECK(operation='TRACE_CREATE'),
    idempotency_key uuid NOT NULL,
    scope varchar(16) NOT NULL CHECK(scope IN ('USER','EVALUATION','BATCH')),
    execution_mode varchar(8) NOT NULL CHECK(execution_mode IN ('MOCK','LIVE')),
    status varchar(16) NOT NULL CHECK(status IN ('QUEUED','RUNNING','COMPLETED','FAILED','CANCELLED')),
    verdict varchar(16) CHECK(verdict IN ('SUPPORTED','HOLD','REJECTED')),
    reason_code varchar(80), summary text,
    pipeline_version varchar(100) NOT NULL,
    config_snapshot jsonb NOT NULL CHECK(jsonb_typeof(config_snapshot)='object'),
    corpus_manifest jsonb CHECK(jsonb_typeof(corpus_manifest)='object'),
    versions jsonb NOT NULL CHECK(jsonb_typeof(versions)='object'),
    checkpoint jsonb NOT NULL DEFAULT '{}' CHECK(jsonb_typeof(checkpoint)='object'),
    requery_count smallint NOT NULL DEFAULT 0 CHECK(requery_count BETWEEN 0 AND 1),
    cancel_requested_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(), started_at timestamptz, completed_at timestamptz,
    UNIQUE(requester_id,operation,idempotency_key),
    CHECK((status='COMPLETED')=(verdict IS NOT NULL)),
    CHECK((status IN ('COMPLETED','FAILED','CANCELLED'))=(completed_at IS NOT NULL))
);
CREATE INDEX trace_runs_library_idx ON trace_runs(question_id,requester_id,created_at DESC,id DESC);
CREATE TABLE processing_jobs (
    id uuid PRIMARY KEY,
    run_id uuid NOT NULL UNIQUE REFERENCES trace_runs(id),
    status varchar(16) NOT NULL CHECK(status IN ('PENDING','QUEUED','RUNNING','RETRY_WAIT','SUCCEEDED','FAILED','CANCELLED')),
    stage varchar(24) NOT NULL CHECK(stage IN ('PREPARE','HYDE','SEARCH','RERANK','VERIFY','PERSIST')),
    stage_attempts jsonb NOT NULL DEFAULT '{}' CHECK(jsonb_typeof(stage_attempts)='object'),
    delivery_count integer NOT NULL DEFAULT 0 CHECK(delivery_count>=0),
    fencing_token bigint NOT NULL DEFAULT 0 CHECK(fencing_token>=0),
    lease_owner varchar(100), lease_until timestamptz, heartbeat_at timestamptz,
    next_attempt_at timestamptz, last_published_at timestamptz,
    publish_attempts integer NOT NULL DEFAULT 0 CHECK(publish_attempts>=0),
    deadline_at timestamptz NOT NULL, last_error_code varchar(80),
    created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(), finished_at timestamptz,
    CHECK(status<>'RUNNING' OR (lease_owner IS NOT NULL AND lease_until IS NOT NULL)),
    CHECK(status<>'RETRY_WAIT' OR next_attempt_at IS NOT NULL),
    CHECK((status IN ('SUCCEEDED','FAILED','CANCELLED'))=(finished_at IS NOT NULL))
);
CREATE INDEX processing_jobs_retry_idx ON processing_jobs(status,next_attempt_at);
CREATE INDEX processing_jobs_lease_idx ON processing_jobs(status,lease_until);
CREATE INDEX processing_jobs_publish_idx ON processing_jobs(status,last_published_at);
CREATE FUNCTION validate_job_attempts() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM jsonb_each(NEW.stage_attempts) AS e
        WHERE e.key NOT IN ('PREPARE','HYDE','SEARCH','RERANK','VERIFY','PERSIST')
        OR jsonb_typeof(e.value)<>'number' OR e.value::text !~ '^[0-3]$') THEN
        RAISE EXCEPTION 'Invalid stage attempts' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER processing_jobs_attempts_check BEFORE INSERT OR UPDATE OF stage_attempts ON processing_jobs
    FOR EACH ROW EXECUTE FUNCTION validate_job_attempts();
CREATE TABLE evidence_results (
    id uuid PRIMARY KEY, run_id uuid NOT NULL REFERENCES trace_runs(id),
    chunk_id uuid NOT NULL REFERENCES law_chunks(id), ordinal integer NOT NULL CHECK(ordinal>=0),
    quote text NOT NULL CHECK(btrim(quote)<>''), start_offset integer NOT NULL CHECK(start_offset>=0),
    end_offset integer NOT NULL CHECK(end_offset>start_offset),
    metadata_snapshot jsonb NOT NULL CHECK(jsonb_typeof(metadata_snapshot)='object'),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE(run_id,ordinal),UNIQUE(run_id,chunk_id,start_offset,end_offset),UNIQUE(run_id,id)
);
