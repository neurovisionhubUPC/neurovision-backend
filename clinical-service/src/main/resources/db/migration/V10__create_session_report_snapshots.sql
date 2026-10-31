-- Additive manual report copies. Existing sessions and their snapshots are not rewritten.
CREATE TABLE session_report_snapshots (
    id UUID PRIMARY KEY,
    clinical_patient_id UUID NOT NULL REFERENCES clinical_patients(id),
    therapy_session_id UUID NOT NULL REFERENCES therapy_sessions(id),
    doctor_cognito_sub VARCHAR(128) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL CHECK (length(btrim(idempotency_key)) BETWEEN 1 AND 128),
    created_at TIMESTAMPTZ NOT NULL,
    -- Preserve the exact outward whitelist encoding, rather than serializing an internal report.
    report_json TEXT NOT NULL CHECK (jsonb_typeof(report_json::jsonb) = 'object'),
    UNIQUE (doctor_cognito_sub, therapy_session_id, idempotency_key)
);

CREATE FUNCTION reject_session_report_snapshot_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'session report snapshots are immutable';
END;
$$;
CREATE TRIGGER session_report_snapshots_immutable BEFORE UPDATE OR DELETE ON session_report_snapshots
    FOR EACH ROW EXECUTE FUNCTION reject_session_report_snapshot_mutation();
