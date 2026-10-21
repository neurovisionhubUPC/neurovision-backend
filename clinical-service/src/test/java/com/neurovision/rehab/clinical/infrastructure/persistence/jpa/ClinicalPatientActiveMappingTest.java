package com.neurovision.rehab.clinical.infrastructure.persistence.jpa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.neurovision.rehab.clinical.domain.model.aggregates.ClinicalPatient;
import com.neurovision.rehab.clinical.domain.model.valueobjects.ClinicalPatientId;
import com.neurovision.rehab.clinical.domain.model.valueobjects.ClinicalPatientStatus;
import com.neurovision.rehab.clinical.domain.model.valueobjects.CognitoSubject;
import com.neurovision.rehab.clinical.domain.model.valueobjects.Sequela;
import com.neurovision.rehab.clinical.domain.model.valueobjects.StrokeDiagnosis;
import com.neurovision.rehab.clinical.infrastructure.persistence.jpa.entities.ClinicalPatientEntity;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ClinicalPatientActiveMappingTest {
    @Test
    void activeWithoutAssessmentRoundTripsWithoutManufacturingValues() {
        var original = patient(ClinicalPatientStatus.ACTIVE, null, Set.of());
        var restored = roundTrip(original);
        assertTrue(restored.strokeDiagnosis().isEmpty());
        assertTrue(restored.sequelae().isEmpty());
    }

    @Test
    void existingAssessmentRoundTripsUnchanged() {
        var original = patient(ClinicalPatientStatus.ACTIVE,
                new StrokeDiagnosis("Previously established diagnosis"),
                Set.of(new Sequela("Reported sequela")));
        roundTrip(original);
    }

    @Test
    void legacyPendingReadDoesNotPerformTheMigration() {
        roundTrip(patient(ClinicalPatientStatus.PENDING_ASSESSMENT, null, Set.of()));
    }

    private ClinicalPatient roundTrip(ClinicalPatient original) {
        var restored = ClinicalPatientEntity.fromDomain(original).toDomain();
        assertEquals(original.id(), restored.id());
        assertEquals(original.patientSubject(), restored.patientSubject());
        assertEquals(original.status(), restored.status());
        assertEquals(original.strokeDiagnosis(), restored.strokeDiagnosis());
        assertEquals(original.sequelae(), restored.sequelae());
        assertEquals(original.createdAt(), restored.createdAt());
        assertEquals(original.updatedAt(), restored.updatedAt());
        return restored;
    }

    private ClinicalPatient patient(ClinicalPatientStatus status, StrokeDiagnosis diagnosis,
            Set<Sequela> sequelae) {
        return ClinicalPatient.rehydrate(new ClinicalPatientId(new UUID(0, 1)),
                new CognitoSubject("synthetic-patient"), status, diagnosis, sequelae,
                Instant.EPOCH, Instant.EPOCH.plusSeconds(60));
    }
}
