CREATE TABLE doctor_invitations (
    id UUID PRIMARY KEY,
    invited_by_admin_sub VARCHAR(128) NOT NULL,
    doctor_cognito_sub VARCHAR(128),
    doctor_email VARCHAR(254) NOT NULL,
    status VARCHAR(16) NOT NULL,
    failure_code VARCHAR(128),
    activated_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_doctor_invitations_status
        CHECK (status IN ('PENDING', 'INVITED', 'ACTIVATED', 'FAILED')),
    CONSTRAINT ck_doctor_invitations_state
        CHECK (
            (status = 'PENDING' AND doctor_cognito_sub IS NULL
                AND failure_code IS NULL AND activated_at IS NULL)
            OR (status = 'INVITED' AND doctor_cognito_sub IS NOT NULL
                AND failure_code IS NULL AND activated_at IS NULL)
            OR (status = 'ACTIVATED' AND doctor_cognito_sub IS NOT NULL
                AND failure_code IS NULL AND activated_at IS NOT NULL)
            OR (status = 'FAILED' AND doctor_cognito_sub IS NULL
                AND failure_code IS NOT NULL AND activated_at IS NULL)
        ),
    CONSTRAINT ck_doctor_invitations_timestamps
        CHECK (
            updated_at >= created_at
            AND (activated_at IS NULL
                OR (activated_at >= created_at AND activated_at <= updated_at))
        )
);

CREATE UNIQUE INDEX uq_doctor_invitations_active_email
    ON doctor_invitations (LOWER(doctor_email))
    WHERE status IN ('PENDING', 'INVITED', 'ACTIVATED');

CREATE UNIQUE INDEX uq_doctor_invitations_doctor_subject
    ON doctor_invitations (doctor_cognito_sub)
    WHERE doctor_cognito_sub IS NOT NULL;
