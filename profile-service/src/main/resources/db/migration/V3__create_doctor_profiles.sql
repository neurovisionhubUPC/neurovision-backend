CREATE TABLE doctor_profiles (
    id UUID PRIMARY KEY,
    profile_id UUID NOT NULL,
    specialty VARCHAR(100) NOT NULL,
    medical_license VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_doctor_profiles_user_profile
        FOREIGN KEY (profile_id) REFERENCES user_profiles(id) ON DELETE CASCADE,
    CONSTRAINT uq_doctor_profiles_profile_id UNIQUE (profile_id),
    CONSTRAINT uq_doctor_profiles_medical_license UNIQUE (medical_license),
    CONSTRAINT ck_doctor_profiles_timestamps CHECK (updated_at >= created_at)
);
