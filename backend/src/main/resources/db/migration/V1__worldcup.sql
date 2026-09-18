CREATE TABLE anonymous_actor (
    id text PRIMARY KEY,
    token_hash text NOT NULL UNIQUE,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE draft (
    id text PRIMARY KEY,
    actor_id text NOT NULL REFERENCES anonymous_actor(id),
    state text NOT NULL CHECK (state IN ('READY', 'REGENERATING', 'FROZEN')),
    version integer NOT NULL CHECK (version > 0),
    regeneration_used integer NOT NULL CHECK (regeneration_used IN (0, 1)),
    input jsonb NOT NULL,
    content jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE generation_job (
    id text PRIMARY KEY,
    actor_id text NOT NULL REFERENCES anonymous_actor(id),
    draft_id text,
    state text NOT NULL CHECK (state IN ('QUEUED', 'RUNNING', 'READY', 'FAILED')),
    input jsonb NOT NULL,
    attempt integer NOT NULL DEFAULT 0 CHECK (attempt BETWEEN 0 AND 2),
    lease_until timestamptz,
    error_code text,
    provider_version text,
    validator_version text,
    created_at timestamptz NOT NULL DEFAULT now(),
    finished_at timestamptz,
    CHECK ((state = 'RUNNING') = (lease_until IS NOT NULL)),
    CHECK ((state = 'FAILED') = (error_code IS NOT NULL)),
    CHECK (state <> 'READY' OR draft_id IS NOT NULL)
);
CREATE INDEX generation_job_pending ON generation_job (created_at) WHERE state IN ('QUEUED', 'RUNNING');
CREATE UNIQUE INDEX one_active_regeneration ON generation_job(draft_id)
    WHERE draft_id IS NOT NULL AND state IN ('QUEUED', 'RUNNING');

-- Source IDs deliberately outlive private drafts/sessions; they are not cascading foreign keys.
CREATE TABLE bracket_snapshot (
    id text PRIMARY KEY,
    source_draft_id text NOT NULL UNIQUE,
    actor_id text NOT NULL REFERENCES anonymous_actor(id),
    candidate_unit text NOT NULL,
    schema_version integer NOT NULL CHECK (schema_version = 1),
    payload jsonb NOT NULL,
    frozen_at timestamptz NOT NULL
);
CREATE FUNCTION reject_snapshot_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Bracket snapshots are immutable' USING ERRCODE = '23514';
END;
$$;
CREATE TRIGGER immutable_snapshot BEFORE UPDATE OR DELETE ON bracket_snapshot
    FOR EACH ROW EXECUTE FUNCTION reject_snapshot_mutation();

CREATE TABLE play_session (
    id text PRIMARY KEY,
    actor_id text NOT NULL REFERENCES anonymous_actor(id),
    snapshot_id text NOT NULL REFERENCES bracket_snapshot(id),
    source_draft_id text UNIQUE,
    state text NOT NULL CHECK (state IN ('PLAYING', 'COMPLETED')),
    next_sequence integer NOT NULL DEFAULT 0 CHECK (next_sequence BETWEEN 0 AND 31),
    champion_id text,
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK ((state = 'COMPLETED') = (champion_id IS NOT NULL))
);
CREATE INDEX session_actor_snapshot ON play_session(actor_id, snapshot_id);
CREATE TABLE pairwise_selection (
    session_id text NOT NULL REFERENCES play_session(id) ON DELETE CASCADE,
    sequence integer NOT NULL CHECK (sequence BETWEEN 0 AND 30),
    event_id text NOT NULL,
    left_id text NOT NULL,
    right_id text NOT NULL,
    winner_id text NOT NULL,
    reason text NOT NULL CHECK (reason IN ('USER_SELECTED', 'TIMEOUT_RANDOM')),
    elapsed_ms bigint NOT NULL CHECK (elapsed_ms >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (session_id, sequence),
    UNIQUE (session_id, event_id),
    CHECK (left_id <> right_id AND winner_id IN (left_id, right_id)),
    CHECK ((reason = 'USER_SELECTED' AND elapsed_ms < 7000) OR (reason = 'TIMEOUT_RANDOM' AND elapsed_ms >= 7000))
);
CREATE TABLE share_link (
    token text PRIMARY KEY,
    snapshot_id text NOT NULL REFERENCES bracket_snapshot(id),
    creator_session_id text NOT NULL UNIQUE,
    champion_id text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE idempotency_request (
    actor_id text NOT NULL REFERENCES anonymous_actor(id),
    route_scope text NOT NULL,
    key text NOT NULL,
    request_hash text NOT NULL,
    response jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (actor_id, route_scope, key)
);
CREATE TABLE generation_quota (
    scope text PRIMARY KEY,
    last_used_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE generation_rate_event (
    scope text NOT NULL REFERENCES generation_quota(scope) ON DELETE CASCADE,
    created_at timestamptz NOT NULL
);
CREATE INDEX rate_events_scope_time ON generation_rate_event(scope, created_at);
