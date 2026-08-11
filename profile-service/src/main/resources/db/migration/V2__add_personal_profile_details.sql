ALTER TABLE user_profiles
    ADD COLUMN first_name VARCHAR(100),
    ADD COLUMN last_name VARCHAR(100),
    ADD COLUMN document_number VARCHAR(32),
    ADD COLUMN birth_date DATE,
    ADD COLUMN phone VARCHAR(20),
    ADD CONSTRAINT uq_user_profiles_document_number UNIQUE (document_number),
    ADD CONSTRAINT ck_user_profiles_personal_details_state
        CHECK (
            (status = 'PENDING_COMPLETION'
                AND first_name IS NULL
                AND last_name IS NULL
                AND document_number IS NULL
                AND birth_date IS NULL
                AND phone IS NULL)
            OR
            (status = 'ACTIVE'
                AND first_name IS NOT NULL
                AND last_name IS NOT NULL
                AND document_number IS NOT NULL
                AND birth_date IS NOT NULL
                AND phone IS NOT NULL)
        );
