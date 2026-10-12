-- The existing vector extension can live outside Flyway's selected application schema.
-- Preserve the application schema first so new tables still land there.
SELECT set_config('search_path',format('%I,%I',current_schema(),
    (SELECT n.nspname FROM pg_extension e JOIN pg_namespace n ON n.oid=e.extnamespace WHERE e.extname='vector')),true);

CREATE TABLE law_chunk_indexes (
    id uuid PRIMARY KEY, chunk_id uuid NOT NULL REFERENCES law_chunks(id),
    embedding_model_revision varchar(160) NOT NULL CHECK(btrim(embedding_model_revision)<>''),
    tokenizer_version varchar(160) NOT NULL CHECK(btrim(tokenizer_version)<>''),
    source_hash char(64) NOT NULL CHECK(source_hash ~ '^[0-9a-f]{64}$'),
    embedding vector(1024), dense_status varchar(16) NOT NULL CHECK(dense_status IN ('PENDING','READY','FAILED')),
    tokens text[], search_vector tsvector,
    lexical_status varchar(16) NOT NULL CHECK(lexical_status IN ('PENDING','READY','FAILED')),
    error_code varchar(80), created_at timestamptz NOT NULL DEFAULT now(),updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE(chunk_id,embedding_model_revision,tokenizer_version,source_hash),
    CHECK((dense_status='READY')=(embedding IS NOT NULL)),
    CHECK((lexical_status='READY')=(tokens IS NOT NULL AND search_vector IS NOT NULL))
);
CREATE INDEX law_chunk_indexes_lexical_idx ON law_chunk_indexes USING gin(search_vector) WHERE lexical_status='READY';
CREATE TABLE question_embeddings (
    id uuid PRIMARY KEY, question_id uuid NOT NULL REFERENCES questions(id),
    question_revision bigint NOT NULL CHECK(question_revision>=1),
    embedding_model_revision varchar(160) NOT NULL CHECK(btrim(embedding_model_revision)<>''),
    input_hash char(64) NOT NULL CHECK(input_hash ~ '^[0-9a-f]{64}$'),
    embedding vector(1024), status varchar(16) NOT NULL CHECK(status IN ('PENDING','READY','FAILED')),
    error_code varchar(80), created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE(question_id,question_revision,embedding_model_revision),
    CHECK((status='READY')=(embedding IS NOT NULL))
);
