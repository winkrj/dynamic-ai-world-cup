-- Separate from job retention: deleting old jobs must not reset a paid-call allowance.
CREATE TABLE provider_call (
    id text PRIMARY KEY,
    job_id text NOT NULL,
    attempt integer NOT NULL CHECK (attempt BETWEEN 1 AND 2),
    stage text NOT NULL,
    model text NOT NULL,
    state text NOT NULL CHECK (state IN ('RESERVED', 'COMPLETED', 'FAILED_UNKNOWN_COST')),
    reserved_usd numeric(16,8) NOT NULL CHECK (reserved_usd > 0),
    accounted_usd numeric(16,8) NOT NULL CHECK (accounted_usd >= 0),
    input_tokens bigint,
    cached_tokens bigint,
    cache_write_tokens bigint,
    output_tokens bigint,
    reasoning_tokens bigint,
    search_calls integer,
    response_id text,
    latency_ms bigint,
    failure_code text,
    created_at timestamptz NOT NULL DEFAULT now(),
    finished_at timestamptz,
    UNIQUE (job_id, attempt, stage)
);
