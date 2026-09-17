-- Preserve the V5 checksum for databases that have already applied it. Replacing
-- the trigger function keeps its existing trigger and all V5 provenance checks.
CREATE OR REPLACE FUNCTION enforce_therapy_session_provenance() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    source_parameters JSONB;
BEGIN
    IF TG_OP = 'UPDATE' AND (
        NEW.id IS DISTINCT FROM OLD.id
        OR NEW.therapy_plan_id IS DISTINCT FROM OLD.therapy_plan_id
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
