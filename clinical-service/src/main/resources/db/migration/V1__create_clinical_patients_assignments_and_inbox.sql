CREATE TABLE clinical_patients (
    id UUID PRIMARY KEY,
    patient_cognito_sub VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL,
    stroke_diagnosis VARCHAR(500),
    sequelae TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[],
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_clinical_patients_cognito_sub UNIQUE (patient_cognito_sub),
    CONSTRAINT ck_clinical_patients_status
        CHECK (status IN ('PENDING_ASSESSMENT', 'ACTIVE')),
    CONSTRAINT ck_clinical_patients_assessment
        CHECK (
            (status = 'PENDING_ASSESSMENT'
                AND stroke_diagnosis IS NULL
                AND cardinality(sequelae) = 0)
            OR (status = 'ACTIVE' AND stroke_diagnosis IS NOT NULL)
        ),
    CONSTRAINT ck_clinical_patients_timestamps
        CHECK (updated_at >= created_at)
);

CREATE TABLE doctor_patient_assignments (
    id UUID PRIMARY KEY,
    clinical_patient_id UUID NOT NULL,
    doctor_cognito_sub VARCHAR(128) NOT NULL,
    status VARCHAR(16) NOT NULL,
    assigned_at TIMESTAMPTZ NOT NULL,
    ended_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_doctor_patient_assignments_patient
        FOREIGN KEY (clinical_patient_id) REFERENCES clinical_patients (id),
    CONSTRAINT ck_doctor_patient_assignments_status
        CHECK (status IN ('ACTIVE', 'ENDED')),
    CONSTRAINT ck_doctor_patient_assignments_state
        CHECK (
            (status = 'ACTIVE' AND ended_at IS NULL)
            OR (status = 'ENDED' AND ended_at IS NOT NULL)
        ),
    CONSTRAINT ck_doctor_patient_assignments_timestamps
        CHECK (ended_at IS NULL OR ended_at >= assigned_at)
);

CREATE UNIQUE INDEX uq_doctor_patient_assignments_active
    ON doctor_patient_assignments (clinical_patient_id, doctor_cognito_sub)
    WHERE status = 'ACTIVE';

CREATE INDEX ix_doctor_patient_assignments_doctor
    ON doctor_patient_assignments (doctor_cognito_sub);

CREATE INDEX ix_doctor_patient_assignments_patient
    ON doctor_patient_assignments (clinical_patient_id);

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
