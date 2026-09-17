ALTER TABLE therapeutic_exercise_configurations
    ADD CONSTRAINT uq_therapeutic_configuration_binding
        UNIQUE (id, therapy_plan_id, exercise_code, version, parameter_schema_version);

ALTER TABLE therapy_sessions
    ADD COLUMN therapeutic_configuration_id UUID,
    ADD COLUMN therapeutic_exercise VARCHAR(32),
    ADD COLUMN parameter_schema_version INTEGER,
    ADD COLUMN configuration_parameters JSONB;

WITH selected AS (
    SELECT DISTINCT ON (session.id)
           session.id AS therapy_session_id,
           configuration.id,
           configuration.version,
           configuration.exercise_code,
           configuration.parameter_schema_version,
           configuration.parameters
    FROM therapy_sessions session
    JOIN therapeutic_exercise_configurations configuration
      ON configuration.therapy_plan_id = session.therapy_plan_id
     AND configuration.exercise_code = CASE session.exercise_code
         WHEN 'visual-scanning' THEN 'VISUAL_SCANNING'
         WHEN 'visual-search' THEN 'VISUAL_SEARCH'
         WHEN 'fixation' THEN 'FIXATION'
     END
     AND configuration.status = 'ACTIVE'
     AND configuration.effective_from <= session.started_at
    ORDER BY session.id, configuration.effective_from DESC, configuration.version DESC
)
UPDATE therapy_sessions session
SET therapeutic_configuration_id = selected.id,
    configuration_version = selected.version,
    therapeutic_exercise = selected.exercise_code,
    parameter_schema_version = selected.parameter_schema_version,
    configuration_parameters = selected.parameters
FROM selected
WHERE session.id = selected.therapy_session_id;

-- V4 fingerprints included the browser-supplied plan configuration version. Phase 2C
-- removes that field from browser intent, so historical rows must be converted to the
-- canonical patient/plan/exercise fingerprint used by the current admission service.
UPDATE therapy_sessions
SET request_fingerprint = encode(
    sha256(convert_to(
        clinical_patient_id::TEXT || E'\n'
        || therapy_plan_id::TEXT || E'\n'
        || exercise_code,
        'UTF8'
    )),
    'hex'
);

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM therapy_sessions WHERE therapeutic_configuration_id IS NULL
    ) THEN
        RAISE EXCEPTION
            'V5 requires an explicit therapeutic-configuration backfill for unmatched therapy sessions';
    END IF;
END;
$$;

ALTER TABLE therapy_sessions
    ALTER COLUMN therapeutic_configuration_id SET NOT NULL,
    ALTER COLUMN therapeutic_exercise SET NOT NULL,
    ALTER COLUMN parameter_schema_version SET NOT NULL,
    ALTER COLUMN configuration_parameters SET NOT NULL,
    ADD CONSTRAINT fk_therapy_session_configuration
        FOREIGN KEY (
            therapeutic_configuration_id,
            therapy_plan_id,
            therapeutic_exercise,
            configuration_version,
            parameter_schema_version
        ) REFERENCES therapeutic_exercise_configurations (
            id,
            therapy_plan_id,
            exercise_code,
            version,
            parameter_schema_version
        ),
    ADD CONSTRAINT ck_therapy_session_therapeutic_exercise
        CHECK (
            (exercise_code = 'visual-scanning' AND therapeutic_exercise = 'VISUAL_SCANNING')
            OR (exercise_code = 'visual-search' AND therapeutic_exercise = 'VISUAL_SEARCH')
            OR (exercise_code = 'fixation' AND therapeutic_exercise = 'FIXATION')
        ),
    ADD CONSTRAINT ck_therapy_session_parameter_snapshot
        CHECK (parameter_schema_version = 1 AND jsonb_typeof(configuration_parameters) = 'object');

CREATE FUNCTION reject_therapeutic_configuration_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'therapeutic exercise configurations are append-only';
END;
$$;

CREATE TRIGGER therapeutic_exercise_configurations_no_update
BEFORE UPDATE OR DELETE ON therapeutic_exercise_configurations
FOR EACH ROW EXECUTE FUNCTION reject_therapeutic_configuration_mutation();

CREATE FUNCTION enforce_therapy_session_provenance() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    source_parameters JSONB;
BEGIN
    IF TG_OP = 'UPDATE' AND (
        NEW.therapy_plan_id IS DISTINCT FROM OLD.therapy_plan_id
        OR NEW.clinical_patient_id IS DISTINCT FROM OLD.clinical_patient_id
        OR NEW.doctor_cognito_sub IS DISTINCT FROM OLD.doctor_cognito_sub
        OR NEW.exercise_code IS DISTINCT FROM OLD.exercise_code
        OR NEW.therapeutic_configuration_id IS DISTINCT FROM OLD.therapeutic_configuration_id
        OR NEW.configuration_version IS DISTINCT FROM OLD.configuration_version
        OR NEW.therapeutic_exercise IS DISTINCT FROM OLD.therapeutic_exercise
        OR NEW.parameter_schema_version IS DISTINCT FROM OLD.parameter_schema_version
        OR NEW.configuration_parameters IS DISTINCT FROM OLD.configuration_parameters
        OR NEW.quota_date IS DISTINCT FROM OLD.quota_date
        OR NEW.idempotency_key IS DISTINCT FROM OLD.idempotency_key
        OR NEW.request_fingerprint IS DISTINCT FROM OLD.request_fingerprint
        OR NEW.minimum_valid_duration_minutes IS DISTINCT FROM OLD.minimum_valid_duration_minutes
        OR NEW.maximum_session_duration_minutes IS DISTINCT FROM OLD.maximum_session_duration_minutes
        OR NEW.minimum_valid_sample_count IS DISTINCT FROM OLD.minimum_valid_sample_count
        OR NEW.started_at IS DISTINCT FROM OLD.started_at
    ) THEN
        RAISE EXCEPTION 'therapy session provenance is immutable';
    END IF;

    SELECT configuration.parameters
      INTO source_parameters
      FROM therapeutic_exercise_configurations configuration
     WHERE configuration.id = NEW.therapeutic_configuration_id
       AND configuration.therapy_plan_id = NEW.therapy_plan_id
       AND configuration.exercise_code = NEW.therapeutic_exercise
       AND configuration.version = NEW.configuration_version
       AND configuration.parameter_schema_version = NEW.parameter_schema_version;

    IF NOT FOUND OR source_parameters IS DISTINCT FROM NEW.configuration_parameters THEN
        RAISE EXCEPTION 'therapy session configuration snapshot must match its bound source';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER therapy_sessions_enforce_provenance
BEFORE INSERT OR UPDATE ON therapy_sessions
FOR EACH ROW EXECUTE FUNCTION enforce_therapy_session_provenance();
