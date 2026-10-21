-- Preserve existing rows and unique constraints. Completion requires names only.
ALTER TABLE user_profiles
    DROP CONSTRAINT ck_user_profiles_personal_details_state,
    ADD CONSTRAINT ck_user_profiles_personal_details_state CHECK (
        (status = 'PENDING_COMPLETION'
            AND first_name IS NULL AND last_name IS NULL
            AND document_number IS NULL AND birth_date IS NULL AND phone IS NULL)
        OR
        (status = 'ACTIVE' AND first_name IS NOT NULL AND last_name IS NOT NULL)
    );

ALTER TABLE doctor_profiles
    ALTER COLUMN specialty DROP NOT NULL,
    ALTER COLUMN medical_license DROP NOT NULL;
