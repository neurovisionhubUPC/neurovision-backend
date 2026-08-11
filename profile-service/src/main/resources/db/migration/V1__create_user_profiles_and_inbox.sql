CREATE TABLE user_profiles (
    id UUID PRIMARY KEY,
    cognito_sub VARCHAR(128) NOT NULL,
    profile_type VARCHAR(16) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_user_profiles_cognito_sub UNIQUE (cognito_sub),
    CONSTRAINT ck_user_profiles_type
        CHECK (profile_type IN ('PATIENT', 'DOCTOR')),
    CONSTRAINT ck_user_profiles_status
        CHECK (status IN ('PENDING_COMPLETION', 'ACTIVE')),
    CONSTRAINT ck_user_profiles_timestamps
        CHECK (updated_at >= created_at)
);

CREATE TABLE inbox_events (
    event_id UUID PRIMARY KEY,
    event_type VARCHAR(128) NOT NULL,
    event_version SMALLINT NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_inbox_events_version CHECK (event_version > 0),
    CONSTRAINT ck_inbox_events_timestamps CHECK (processed_at >= occurred_at)
);

CREATE INDEX ix_inbox_events_processed_at ON inbox_events (processed_at);
