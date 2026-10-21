package com.neurovision.rehab.clinical.infrastructure.persistence.jpa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** Offline SQL contract checks only; these do not execute a database migration. */
class ClinicalPatientActivationMigrationTest {
    @Test
    void migrationChangesOnlyTheAssessmentConstraintAndPendingStatusVersion() throws IOException {
        var sql = resource("V7__separate_clinical_registration_from_assessment.sql");
        var statements = Arrays.stream(sql.replaceAll("(?m)--[^\\r\\n]*", "").split(";"))
                .map(s -> s.replaceAll("\\s+", " ").trim()
                        .replaceAll("\\( +", "(").replaceAll(" +\\)", ")"))
                .filter(s -> !s.isEmpty()).toList();

        assertEquals(3, statements.size());
        assertEquals("ALTER TABLE clinical_patients DROP CONSTRAINT ck_clinical_patients_assessment",
                statements.get(0));
        assertEquals("ALTER TABLE clinical_patients ADD CONSTRAINT ck_clinical_patients_assessment "
                + "CHECK (status = 'ACTIVE' OR (status = 'PENDING_ASSESSMENT' "
                + "AND stroke_diagnosis IS NULL AND cardinality(sequelae) = 0))", statements.get(1));
        assertEquals("UPDATE clinical_patients SET status = 'ACTIVE', version = version + 1 "
                + "WHERE status = 'PENDING_ASSESSMENT'", statements.get(2));
    }

    @Test
    void originalMigrationStillDefinesIdentityPermissionAndTimestampConstraints() throws IOException {
        var original = resource("V1__create_clinical_patients_assignments_and_inbox.sql");
        assertTrue(original.contains("OR (status = 'ACTIVE' AND stroke_diagnosis IS NOT NULL)"));
        assertTrue(original.contains("UNIQUE (patient_cognito_sub)"));
        assertTrue(original.contains("CHECK (updated_at >= created_at)"));
        assertTrue(original.contains("CREATE UNIQUE INDEX uq_doctor_patient_assignments_active"));
    }

    private String resource(String name) throws IOException {
        try (var input = getClass().getResourceAsStream("/db/migration/" + name)) {
            assertNotNull(input, "Expected versioned migration resource: " + name);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
