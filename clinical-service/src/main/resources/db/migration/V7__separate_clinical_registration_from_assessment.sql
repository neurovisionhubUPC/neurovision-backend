-- ACTIVE describes a registered Clinical record, not a diagnosis or account permission.
-- Apply through transactional Flyway with old Clinical readers/writers stopped.
-- Old binaries cannot rehydrate ACTIVE records without a diagnosis.
ALTER TABLE clinical_patients
    DROP CONSTRAINT ck_clinical_patients_assessment;

-- Keep the legacy value readable during the compatibility transition.
-- Identity, assignment, timestamp, uniqueness and status-domain constraints remain intact.
ALTER TABLE clinical_patients
    ADD CONSTRAINT ck_clinical_patients_assessment
    CHECK (
        status = 'ACTIVE'
        OR (status = 'PENDING_ASSESSMENT'
            AND stroke_diagnosis IS NULL
            AND cardinality(sequelae) = 0)
    );

-- Preserve clinical fields and business timestamps. Invalidate stale ORM versions only.
UPDATE clinical_patients
SET status = 'ACTIVE', version = version + 1
WHERE status = 'PENDING_ASSESSMENT';
