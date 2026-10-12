CREATE TABLE laws (
    id uuid PRIMARY KEY,
    source_provider varchar(40) NOT NULL CHECK (btrim(source_provider)<>''),
    source_law_id varchar(100) NOT NULL CHECK (btrim(source_law_id)<>''),
    title text NOT NULL CHECK (char_length(title) BETWEEN 1 AND 200 AND btrim(title)<>''),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE(source_provider,source_law_id)
);
CREATE TABLE law_versions (
    id uuid PRIMARY KEY,
    law_id uuid NOT NULL REFERENCES laws(id),
    source_version_key varchar(160) NOT NULL CHECK (btrim(source_version_key)<>''),
    effective_from date NOT NULL,
    effective_to date,
    applicability_status varchar(24) NOT NULL CHECK (applicability_status IN ('VERIFIED','REVIEW_REQUIRED')),
    source_url text NOT NULL CHECK (btrim(source_url)<>''),
    raw_payload jsonb NOT NULL CHECK (jsonb_typeof(raw_payload)='object'),
    content_hash char(64) NOT NULL CHECK (content_hash ~ '^[0-9a-f]{64}$'),
    fetched_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE(law_id,source_version_key),
    CHECK(effective_to IS NULL OR effective_to>effective_from)
);
CREATE INDEX law_versions_effective_idx ON law_versions(law_id,effective_from);
CREATE TABLE law_articles (
    id uuid PRIMARY KEY,
    law_version_id uuid NOT NULL REFERENCES law_versions(id),
    article_key varchar(160) NOT NULL CHECK(btrim(article_key)<>''),
    article_label varchar(200) NOT NULL CHECK(btrim(article_label)<>''),
    source_text text NOT NULL CHECK(char_length(source_text) BETWEEN 1 AND 200000 AND btrim(source_text)<>''),
    content_hash char(64) NOT NULL CHECK(content_hash ~ '^[0-9a-f]{64}$'),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE(law_version_id,article_key)
);
CREATE TABLE law_chunks (
    id uuid PRIMARY KEY,
    article_id uuid NOT NULL REFERENCES law_articles(id),
    chunker_version varchar(100) NOT NULL CHECK(btrim(chunker_version)<>''),
    ordinal integer NOT NULL CHECK(ordinal>=0),
    article_start integer NOT NULL CHECK(article_start>=0),
    article_end integer NOT NULL CHECK(article_end>article_start),
    source_text text NOT NULL CHECK(btrim(source_text)<>''),
    search_text text NOT NULL CHECK(btrim(search_text)<>''),
    content_hash char(64) NOT NULL CHECK(content_hash ~ '^[0-9a-f]{64}$'),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE(article_id,chunker_version,ordinal)
);
