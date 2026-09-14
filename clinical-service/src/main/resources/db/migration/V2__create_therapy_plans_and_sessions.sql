CREATE TABLE therapy_plans (
    id UUID PRIMARY KEY,
    clinical_patient_id UUID NOT NULL REFERENCES clinical_patients (id),
    doctor_cognito_sub VARCHAR(128) NOT NULL,
    status VARCHAR(16) NOT NULL,
    configuration_version BIGINT NOT NULL,
    daily_session_limit INTEGER NOT NULL,
    daily_target_duration_minutes INTEGER NOT NULL,
    daily_hard_safety_limit_minutes INTEGER,
    minimum_valid_duration_minutes INTEGER NOT NULL,
    maximum_session_duration_minutes INTEGER NOT NULL,
    minimum_valid_sample_count BIGINT NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_therapy_plans_status CHECK (status IN ('ACTIVE', 'SUSPENDED', 'CANCELLED')),
    CONSTRAINT ck_therapy_plans_doctor_subject CHECK (
        char_length(btrim(doctor_cognito_sub)) BETWEEN 1 AND 128
    ),
    CONSTRAINT ck_therapy_plans_positive_limits CHECK (
        configuration_version > 0 AND daily_session_limit > 0
        AND daily_target_duration_minutes > 0 AND minimum_valid_duration_minutes > 0
        AND maximum_session_duration_minutes >= minimum_valid_duration_minutes
        AND minimum_valid_sample_count > 0
    ),
    CONSTRAINT ck_therapy_plans_hard_limit CHECK (
        daily_hard_safety_limit_minutes IS NULL
        OR daily_hard_safety_limit_minutes >= maximum_session_duration_minutes
    )
);

CREATE INDEX ix_therapy_plans_patient ON therapy_plans (clinical_patient_id);
CREATE INDEX ix_therapy_plans_doctor ON therapy_plans (doctor_cognito_sub);

CREATE TABLE therapy_plan_exercise_prescriptions (
    therapy_plan_id UUID NOT NULL REFERENCES therapy_plans (id) ON DELETE CASCADE,
    exercise_code VARCHAR(100) NOT NULL,
    daily_quantity INTEGER NOT NULL,
    PRIMARY KEY (therapy_plan_id, exercise_code),
    CONSTRAINT ck_therapy_plan_exercise_code CHECK (
        exercise_code ~ '^[a-z0-9][a-z0-9._-]{0,99}$'
    ),
    CONSTRAINT ck_therapy_plan_exercise_quantity CHECK (daily_quantity > 0)
);

CREATE TABLE therapy_sessions (
    id UUID PRIMARY KEY,
    therapy_plan_id UUID NOT NULL REFERENCES therapy_plans (id),
    clinical_patient_id UUID NOT NULL REFERENCES clinical_patients (id),
    doctor_cognito_sub VARCHAR(128) NOT NULL,
    exercise_code VARCHAR(100) NOT NULL,
    configuration_version BIGINT NOT NULL,
    quota_date DATE NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    minimum_valid_duration_minutes INTEGER NOT NULL,
    maximum_session_duration_minutes INTEGER NOT NULL,
    minimum_valid_sample_count BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    active_duration_seconds BIGINT NOT NULL DEFAULT 0,
    valid_sample_count BIGINT NOT NULL DEFAULT 0,
    consumes_daily_quota BOOLEAN NOT NULL DEFAULT TRUE,
    started_at TIMESTAMPTZ NOT NULL,
    lease_expires_at TIMESTAMPTZ,
    ended_at TIMESTAMPTZ,
    termination_reason VARCHAR(500),
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_therapy_sessions_idempotency UNIQUE (clinical_patient_id, idempotency_key),
    CONSTRAINT ck_therapy_sessions_status CHECK (status IN ('ACTIVE', 'COMPLETED', 'CANCELLED', 'ABANDONED', 'FAILED_TECHNICAL')),
    CONSTRAINT ck_therapy_sessions_text_bounds CHECK (
        char_length(btrim(doctor_cognito_sub)) BETWEEN 1 AND 128
        AND exercise_code ~ '^[a-z0-9][a-z0-9._-]{0,99}$'
        AND char_length(btrim(idempotency_key)) BETWEEN 1 AND 128
        AND request_fingerprint ~ '^[0-9a-f]{64}$'
        AND (termination_reason IS NULL OR char_length(btrim(termination_reason)) BETWEEN 1 AND 500)
    ),
    CONSTRAINT ck_therapy_sessions_evidence CHECK (
        active_duration_seconds >= 0
        AND active_duration_seconds <= maximum_session_duration_minutes * 60::BIGINT
        AND valid_sample_count >= 0
    ),
    CONSTRAINT ck_therapy_sessions_frozen_limits CHECK (
        configuration_version > 0 AND minimum_valid_duration_minutes > 0
        AND maximum_session_duration_minutes >= minimum_valid_duration_minutes
        AND minimum_valid_sample_count > 0
    ),
    CONSTRAINT ck_therapy_sessions_state CHECK (
        (status = 'ACTIVE' AND consumes_daily_quota AND lease_expires_at IS NOT NULL AND ended_at IS NULL AND termination_reason IS NULL)
        OR (status = 'COMPLETED' AND consumes_daily_quota AND lease_expires_at IS NULL AND ended_at IS NOT NULL AND termination_reason IS NULL)
        OR (status IN ('CANCELLED', 'ABANDONED', 'FAILED_TECHNICAL') AND lease_expires_at IS NULL AND ended_at IS NOT NULL AND termination_reason IS NOT NULL)
    ),
    CONSTRAINT ck_therapy_sessions_timestamps CHECK (ended_at IS NULL OR ended_at >= started_at)
);

CREATE UNIQUE INDEX uq_therapy_sessions_active_patient
    ON therapy_sessions (clinical_patient_id) WHERE status = 'ACTIVE';
CREATE INDEX ix_therapy_sessions_plan_quota
    ON therapy_sessions (therapy_plan_id, quota_date);
CREATE INDEX ix_therapy_sessions_expired_lease
    ON therapy_sessions (lease_expires_at) WHERE status = 'ACTIVE';
