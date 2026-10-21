-- Optional manual context; existing diagnosis, sequelae, permissions and status remain unchanged.
ALTER TABLE clinical_patients
    ADD COLUMN affected_hemifield VARCHAR(5)
        CONSTRAINT ck_clinical_patients_hemifield CHECK (affected_hemifield IN ('LEFT', 'RIGHT')),
    ADD COLUMN rehabilitation_observations VARCHAR(2000);
