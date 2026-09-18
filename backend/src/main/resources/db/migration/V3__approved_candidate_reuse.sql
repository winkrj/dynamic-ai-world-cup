-- Complete sets only. No raw request/history; hashes are equality keys, not anonymization.
CREATE TABLE candidate_reuse_set (
    id text PRIMARY KEY,
    context_hash text NOT NULL CHECK (length(context_hash) = 64),
    membership_hash text NOT NULL CHECK (length(membership_hash) = 64),
    policy_version text NOT NULL,
    -- Deliberately no FK: private job/draft retention must remain independent.
    source_job_id text NOT NULL,
    source_attempt integer NOT NULL CHECK (source_attempt BETWEEN 1 AND 2),
    state text NOT NULL CHECK (state IN ('PENDING', 'APPROVED', 'REJECTED', 'REVOKED')),
    public_title text NOT NULL,
    provider_version text NOT NULL,
    validator_version text NOT NULL,
    certificate jsonb NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    approved_at timestamptz,
    expires_at timestamptz,
    quality_approved boolean NOT NULL DEFAULT false,
    public_safe boolean NOT NULL DEFAULT false,
    time_independent boolean NOT NULL DEFAULT false,
    UNIQUE (source_job_id, source_attempt),
    CHECK (state <> 'APPROVED' OR (quality_approved AND public_safe AND time_independent
        AND approved_at IS NOT NULL AND expires_at IS NOT NULL
        AND expires_at > approved_at AND expires_at <= approved_at + interval '7 days'))
);
CREATE INDEX candidate_reuse_lookup ON candidate_reuse_set(context_hash, expires_at) WHERE state = 'APPROVED';
CREATE FUNCTION protect_reuse_certificate() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (NEW.context_hash, NEW.membership_hash, NEW.policy_version, NEW.source_job_id, NEW.source_attempt,
        NEW.public_title, NEW.provider_version, NEW.validator_version, NEW.certificate, NEW.created_at)
        IS DISTINCT FROM
       (OLD.context_hash, OLD.membership_hash, OLD.policy_version, OLD.source_job_id, OLD.source_attempt,
        OLD.public_title, OLD.provider_version, OLD.validator_version, OLD.certificate, OLD.created_at) THEN
        RAISE EXCEPTION 'Original candidate reuse evidence is immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER immutable_reuse_certificate BEFORE UPDATE ON candidate_reuse_set
    FOR EACH ROW EXECUTE FUNCTION protect_reuse_certificate();

CREATE TABLE candidate_reuse_review (
    id text PRIMARY KEY,
    set_id text NOT NULL,
    action text NOT NULL CHECK (action IN ('APPROVED', 'REJECTED', 'REVOKED')),
    operator_id text NOT NULL,
    quality_review text,
    public_safety_review text,
    time_independent boolean NOT NULL DEFAULT false,
    reason text,
    expires_at timestamptz,
    created_at timestamptz NOT NULL,
    CHECK (action <> 'APPROVED' OR (quality_review IS NOT NULL AND public_safety_review IS NOT NULL
        AND time_independent AND expires_at IS NOT NULL))
);
CREATE TABLE candidate_reuse_use (
    job_id text NOT NULL,
    attempt integer NOT NULL CHECK (attempt BETWEEN 1 AND 2),
    set_id text NOT NULL,
    created_at timestamptz NOT NULL,
    PRIMARY KEY(job_id, attempt)
);
