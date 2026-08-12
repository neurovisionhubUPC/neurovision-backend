DROP INDEX uq_patient_invitations_active_email;

ALTER TABLE patient_invitations
    DROP CONSTRAINT ck_patient_invitations_state,
    DROP CONSTRAINT ck_patient_invitations_status;

UPDATE patient_invitations
SET status = 'INVITED'
WHERE status = 'SENT';

ALTER TABLE patient_invitations
    ADD COLUMN activated_at TIMESTAMPTZ,
    ADD CONSTRAINT ck_patient_invitations_status
        CHECK (status IN ('PENDING', 'INVITED', 'ACTIVATED', 'FAILED')),
    ADD CONSTRAINT ck_patient_invitations_state
        CHECK (
            (status = 'PENDING'
                AND patient_cognito_sub IS NULL
                AND failure_code IS NULL
                AND activated_at IS NULL)
            OR (status = 'INVITED'
                AND patient_cognito_sub IS NOT NULL
                AND failure_code IS NULL
                AND activated_at IS NULL)
            OR (status = 'ACTIVATED'
                AND patient_cognito_sub IS NOT NULL
                AND failure_code IS NULL
                AND activated_at IS NOT NULL)
            OR (status = 'FAILED'
                AND patient_cognito_sub IS NULL
                AND failure_code IS NOT NULL
                AND activated_at IS NULL)
        ),
    ADD CONSTRAINT ck_patient_invitations_activation_time
        CHECK (activated_at IS NULL OR (activated_at >= created_at AND activated_at <= updated_at));

CREATE UNIQUE INDEX uq_patient_invitations_active_email
    ON patient_invitations (LOWER(patient_email))
    WHERE status IN ('PENDING', 'INVITED', 'ACTIVATED');
