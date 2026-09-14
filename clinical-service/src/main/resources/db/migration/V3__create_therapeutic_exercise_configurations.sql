CREATE TABLE therapeutic_exercise_configurations (
    id UUID PRIMARY KEY,
    therapy_plan_id UUID NOT NULL REFERENCES therapy_plans (id),
    exercise_code VARCHAR(32) NOT NULL,
    version BIGINT NOT NULL,
    parameter_schema_version INTEGER NOT NULL,
    effective_from TIMESTAMPTZ NOT NULL,
    created_by_doctor_subject VARCHAR(128) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(16) NOT NULL,
    parameters JSONB NOT NULL,
    CONSTRAINT uq_therapeutic_configuration_version
        UNIQUE (therapy_plan_id, exercise_code, version),
    CONSTRAINT ck_therapeutic_configuration_exercise
        CHECK (exercise_code IN ('VISUAL_SCANNING', 'VISUAL_SEARCH', 'FIXATION')),
    CONSTRAINT ck_therapeutic_configuration_version
        CHECK (version > 0 AND parameter_schema_version = 1),
    CONSTRAINT ck_therapeutic_configuration_author
        CHECK (char_length(btrim(created_by_doctor_subject)) BETWEEN 1 AND 128),
    CONSTRAINT ck_therapeutic_configuration_status
        CHECK (status IN ('ACTIVE', 'REVOKED')),
    CONSTRAINT ck_therapeutic_configuration_parameters
        CHECK (jsonb_typeof(parameters) = 'object'),
    CONSTRAINT ck_therapeutic_configuration_timestamps
        CHECK (effective_from >= created_at)
);

CREATE INDEX ix_therapeutic_configuration_effective
    ON therapeutic_exercise_configurations (therapy_plan_id, exercise_code, effective_from DESC);
