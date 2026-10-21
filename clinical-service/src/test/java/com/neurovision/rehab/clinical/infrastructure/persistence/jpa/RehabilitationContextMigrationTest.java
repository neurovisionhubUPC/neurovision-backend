package com.neurovision.rehab.clinical.infrastructure.persistence.jpa;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Structural checks only; this test never applies SQL or starts a database. */
class RehabilitationContextMigrationTest {
    @Test void v8AddsOnlyNullableContextColumnsWithoutRewritingExistingAssessment() throws Exception {
        try (var input = getClass().getResourceAsStream("/db/migration/V8__add_optional_rehabilitation_context.sql")) {
            assertNotNull(input);
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8)
                    .replaceAll("(?m)--[^\\r\\n]*", "").replaceAll("\\s+", " ").trim();
            assertEquals("ALTER TABLE clinical_patients "
                    + "ADD COLUMN affected_hemifield VARCHAR(5) "
                    + "CONSTRAINT ck_clinical_patients_hemifield CHECK (affected_hemifield IN ('LEFT', 'RIGHT')), "
                    + "ADD COLUMN rehabilitation_observations VARCHAR(2000);", sql);
        }
    }
}
