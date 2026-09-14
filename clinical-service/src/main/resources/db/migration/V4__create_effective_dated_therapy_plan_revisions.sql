CREATE TABLE therapy_plan_revisions (
    id UUID PRIMARY KEY,
    therapy_plan_id UUID NOT NULL REFERENCES therapy_plans (id),
    sequence BIGINT NOT NULL,
    command_id UUID NOT NULL,
    command_fingerprint VARCHAR(64) NOT NULL,
    revision_kind VARCHAR(16) NOT NULL,
    effective_from TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    created_by_doctor_subject VARCHAR(128) NOT NULL,
    plan_status VARCHAR(16),
    daily_session_limit INTEGER,
    daily_target_duration_minutes INTEGER,
    daily_hard_safety_limit_minutes INTEGER,
    has_exercise_distribution BOOLEAN NOT NULL DEFAULT FALSE,
    clinical_reason VARCHAR(500),
    CONSTRAINT uq_therapy_plan_revision_sequence UNIQUE (therapy_plan_id, sequence),
    CONSTRAINT uq_therapy_plan_revision_command_kind
        UNIQUE (therapy_plan_id, command_id, revision_kind),
    CONSTRAINT ck_therapy_plan_revision_sequence CHECK (sequence > 0),
    CONSTRAINT ck_therapy_plan_revision_fingerprint
        CHECK (command_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_therapy_plan_revision_kind
        CHECK (revision_kind IN ('IMMEDIATE', 'DEFERRED')),
    CONSTRAINT ck_therapy_plan_revision_author
        CHECK (char_length(btrim(created_by_doctor_subject)) BETWEEN 1 AND 128),
    CONSTRAINT ck_therapy_plan_revision_status
        CHECK (plan_status IS NULL OR plan_status IN ('SUSPENDED', 'CANCELLED')),
    CONSTRAINT ck_therapy_plan_revision_positive_values CHECK (
        (daily_session_limit IS NULL OR daily_session_limit > 0)
        AND (daily_target_duration_minutes IS NULL OR daily_target_duration_minutes > 0)
        AND (daily_hard_safety_limit_minutes IS NULL OR daily_hard_safety_limit_minutes > 0)
    ),
    CONSTRAINT ck_therapy_plan_revision_reason CHECK (
        (plan_status IS NULL OR clinical_reason IS NOT NULL)
        AND (clinical_reason IS NULL OR char_length(btrim(clinical_reason)) BETWEEN 1 AND 500)
    ),
    CONSTRAINT ck_therapy_plan_revision_has_change CHECK (
        plan_status IS NOT NULL OR daily_session_limit IS NOT NULL
        OR daily_target_duration_minutes IS NOT NULL
        OR daily_hard_safety_limit_minutes IS NOT NULL
        OR has_exercise_distribution
    ),
    CONSTRAINT ck_therapy_plan_revision_timestamps CHECK (effective_from >= created_at)
);

CREATE TABLE therapy_plan_revision_exercises (
    therapy_plan_revision_id UUID NOT NULL
        REFERENCES therapy_plan_revisions (id) ON DELETE CASCADE,
    exercise_code VARCHAR(100) NOT NULL,
    daily_quantity INTEGER NOT NULL,
    PRIMARY KEY (therapy_plan_revision_id, exercise_code),
    CONSTRAINT ck_therapy_plan_revision_exercise_code CHECK (
        exercise_code ~ '^[a-z0-9][a-z0-9._-]{0,99}$'
    ),
    CONSTRAINT ck_therapy_plan_revision_exercise_quantity CHECK (daily_quantity > 0)
);

CREATE INDEX ix_therapy_plan_revisions_effective
    ON therapy_plan_revisions (therapy_plan_id, effective_from, sequence);
CREATE INDEX ix_therapy_plan_revisions_command
    ON therapy_plan_revisions (therapy_plan_id, command_id);

CREATE FUNCTION reject_therapy_plan_revision_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'therapy plan revisions are append-only';
END;
$$;

CREATE TRIGGER therapy_plan_revisions_no_update
BEFORE UPDATE OR DELETE ON therapy_plan_revisions
FOR EACH ROW EXECUTE FUNCTION reject_therapy_plan_revision_mutation();

CREATE TRIGGER therapy_plan_revision_exercises_no_update
BEFORE UPDATE OR DELETE ON therapy_plan_revision_exercises
FOR EACH ROW EXECUTE FUNCTION reject_therapy_plan_revision_mutation();
