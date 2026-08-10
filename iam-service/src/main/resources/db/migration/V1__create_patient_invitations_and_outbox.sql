CREATE TABLE patient_invitations (
    id UUID PRIMARY KEY,
    doctor_cognito_sub VARCHAR(128) NOT NULL,
    patient_cognito_sub VARCHAR(128),
    patient_email VARCHAR(254) NOT NULL,
    status VARCHAR(16) NOT NULL,
    failure_code VARCHAR(128),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_patient_invitations_status
        CHECK (status IN ('PENDING', 'SENT', 'FAILED')),
    CONSTRAINT ck_patient_invitations_state
        CHECK (
            (status = 'PENDING' AND patient_cognito_sub IS NULL AND failure_code IS NULL)
            OR (status = 'SENT' AND patient_cognito_sub IS NOT NULL AND failure_code IS NULL)
            OR (status = 'FAILED' AND patient_cognito_sub IS NULL AND failure_code IS NOT NULL)
        ),
    CONSTRAINT ck_patient_invitations_timestamps
        CHECK (updated_at >= created_at)
);

CREATE UNIQUE INDEX uq_patient_invitations_active_email
    ON patient_invitations (LOWER(patient_email))
    WHERE status IN ('PENDING', 'SENT');

CREATE UNIQUE INDEX uq_patient_invitations_patient_subject
    ON patient_invitations (patient_cognito_sub)
    WHERE patient_cognito_sub IS NOT NULL;

CREATE TABLE outbox_events (
    id UUID PRIMARY KEY,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(128) NOT NULL,
    event_version SMALLINT NOT NULL,
    payload JSONB NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMPTZ,
    publish_attempts INTEGER NOT NULL DEFAULT 0,
    last_error TEXT,
    CONSTRAINT ck_outbox_events_version CHECK (event_version > 0),
    CONSTRAINT ck_outbox_events_attempts CHECK (publish_attempts >= 0)
);

CREATE INDEX ix_outbox_events_unpublished
    ON outbox_events (occurred_at, id)
    WHERE published_at IS NULL;
