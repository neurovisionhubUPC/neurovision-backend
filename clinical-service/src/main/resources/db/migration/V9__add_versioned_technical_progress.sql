-- Preserve all legacy configuration/snapshot bytes. Only schema 2 opts in to fulfillment.
ALTER TABLE therapeutic_exercise_configurations DROP CONSTRAINT ck_therapeutic_configuration_version;
ALTER TABLE therapeutic_exercise_configurations ADD CONSTRAINT ck_therapeutic_configuration_version
    CHECK (version > 0 AND parameter_schema_version IN (1, 2));
ALTER TABLE therapy_sessions DROP CONSTRAINT ck_therapy_session_parameter_snapshot;
ALTER TABLE therapy_sessions ADD CONSTRAINT ck_therapy_session_parameter_snapshot
    CHECK (parameter_schema_version IN (1, 2) AND jsonb_typeof(configuration_parameters) = 'object');

CREATE TABLE therapy_session_progress (
    therapy_session_id UUID PRIMARY KEY REFERENCES therapy_sessions(id),
    state JSONB NOT NULL CHECK (jsonb_typeof(state) = 'object'),
    completion JSONB CHECK (completion IS NULL OR jsonb_typeof(completion) = 'object')
);
CREATE TABLE therapy_session_activity_receipts (
    id VARCHAR(73) PRIMARY KEY,
    therapy_session_id UUID NOT NULL REFERENCES therapy_sessions(id),
    event_sequence BIGINT NOT NULL CHECK (event_sequence >= 0),
    receipt JSONB NOT NULL CHECK (jsonb_typeof(receipt) = 'object'),
    UNIQUE (therapy_session_id, event_sequence)
);

CREATE FUNCTION require_explicit_progress_configuration() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM therapy_sessions WHERE id = NEW.therapy_session_id AND parameter_schema_version = 2) THEN
        RAISE EXCEPTION 'technical progress requires an explicit configuration';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER therapy_progress_configuration BEFORE INSERT ON therapy_session_progress
    FOR EACH ROW EXECUTE FUNCTION require_explicit_progress_configuration();
CREATE TRIGGER therapy_activity_configuration BEFORE INSERT ON therapy_session_activity_receipts
    FOR EACH ROW EXECUTE FUNCTION require_explicit_progress_configuration();
