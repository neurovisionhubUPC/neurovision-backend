package com.neurovision.rehab.clinical.domain.model.aggregates;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.neurovision.rehab.clinical.domain.model.valueobjects.ClinicalPatientId;
import com.neurovision.rehab.clinical.domain.model.valueobjects.ClinicalPatientStatus;
import com.neurovision.rehab.clinical.domain.model.valueobjects.CognitoSubject;
import com.neurovision.rehab.clinical.domain.model.valueobjects.Sequela;
import com.neurovision.rehab.clinical.domain.model.valueobjects.StrokeDiagnosis;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ClinicalPatientTest {

    private static final ClinicalPatientId PATIENT_ID =
            new ClinicalPatientId(UUID.fromString("f018d2f8-1142-4f57-b1f3-bf18e2823b75"));
    private static final CognitoSubject PATIENT_SUBJECT =
            new CognitoSubject("84d84438-f031-70ea-fd6e-3b4011bbb46e");
    private static final Instant REGISTERED_AT = Instant.parse("2026-08-11T05:00:00Z");

    @Test
    void shouldRegisterAnInvitedPatientPendingClinicalAssessment() {
        var patient = ClinicalPatient.registerInvited(PATIENT_ID, PATIENT_SUBJECT, REGISTERED_AT);

        assertEquals(PATIENT_ID, patient.id());
        assertEquals(PATIENT_SUBJECT, patient.patientSubject());
        assertEquals(ClinicalPatientStatus.PENDING_ASSESSMENT, patient.status());
        assertTrue(patient.strokeDiagnosis().isEmpty());
        assertTrue(patient.sequelae().isEmpty());
        assertEquals(REGISTERED_AT, patient.createdAt());
        assertEquals(REGISTERED_AT, patient.updatedAt());
    }

    @Test
    void shouldRecordThePostStrokeAssessment() {
        var patient = ClinicalPatient.registerInvited(PATIENT_ID, PATIENT_SUBJECT, REGISTERED_AT);
        var diagnosis = new StrokeDiagnosis("Ischemic stroke");
        var sequelae = Set.of(new Sequela("Left hemiparesis"), new Sequela("Visual neglect"));
        var assessedAt = REGISTERED_AT.plusSeconds(60);

        patient.recordAssessment(diagnosis, sequelae, assessedAt);

        assertEquals(ClinicalPatientStatus.ACTIVE, patient.status());
        assertEquals(diagnosis, patient.strokeDiagnosis().orElseThrow());
        assertEquals(sequelae, patient.sequelae());
        assertEquals(assessedAt, patient.updatedAt());
    }

    @Test
    void shouldAllowAnAssessmentWithoutRegisteredSequelae() {
        var patient = ClinicalPatient.registerInvited(PATIENT_ID, PATIENT_SUBJECT, REGISTERED_AT);

        patient.recordAssessment(
                new StrokeDiagnosis("Ischemic stroke"), Set.of(), REGISTERED_AT.plusSeconds(60));

        assertEquals(ClinicalPatientStatus.ACTIVE, patient.status());
        assertTrue(patient.sequelae().isEmpty());
    }

    @Test
    void shouldKeepAnIdenticalAssessmentIdempotent() {
        var patient = ClinicalPatient.registerInvited(PATIENT_ID, PATIENT_SUBJECT, REGISTERED_AT);
        var diagnosis = new StrokeDiagnosis("Ischemic stroke");
        var sequelae = Set.of(new Sequela("Visual neglect"));
        var assessedAt = REGISTERED_AT.plusSeconds(60);
        patient.recordAssessment(diagnosis, sequelae, assessedAt);

        patient.recordAssessment(diagnosis, sequelae, assessedAt.plusSeconds(60));

        assertEquals(assessedAt, patient.updatedAt());
    }

    @Test
    void shouldRejectAnAssessmentBeforeTheLastUpdate() {
        var patient = ClinicalPatient.registerInvited(PATIENT_ID, PATIENT_SUBJECT, REGISTERED_AT);

        assertThrows(
                IllegalArgumentException.class,
                () -> patient.recordAssessment(
                        new StrokeDiagnosis("Ischemic stroke"),
                        Set.of(),
                        REGISTERED_AT.minusSeconds(1)));
    }

    @Test
    void shouldRejectNullAssessmentData() {
        var patient = ClinicalPatient.registerInvited(PATIENT_ID, PATIENT_SUBJECT, REGISTERED_AT);

        assertThrows(
                NullPointerException.class,
                () -> patient.recordAssessment(null, Set.of(), REGISTERED_AT.plusSeconds(1)));
        assertThrows(
                NullPointerException.class,
                () -> patient.recordAssessment(
                        new StrokeDiagnosis("Ischemic stroke"),
                        null,
                        REGISTERED_AT.plusSeconds(1)));
    }

    @Test
    void shouldRejectInconsistentRehydratedState() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ClinicalPatient.rehydrate(
                        PATIENT_ID,
                        PATIENT_SUBJECT,
                        ClinicalPatientStatus.PENDING_ASSESSMENT,
                        new StrokeDiagnosis("Ischemic stroke"),
                        Set.of(),
                        REGISTERED_AT,
                        REGISTERED_AT));
        assertThrows(
                IllegalArgumentException.class,
                () -> ClinicalPatient.rehydrate(
                        PATIENT_ID,
                        PATIENT_SUBJECT,
                        ClinicalPatientStatus.ACTIVE,
                        null,
                        Set.of(),
                        REGISTERED_AT,
                        REGISTERED_AT));
    }

    @Test
    void shouldRejectAnUpdateTimeBeforeCreation() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ClinicalPatient.rehydrate(
                        PATIENT_ID,
                        PATIENT_SUBJECT,
                        ClinicalPatientStatus.PENDING_ASSESSMENT,
                        null,
                        Set.of(),
                        REGISTERED_AT,
                        REGISTERED_AT.minusSeconds(1)));
    }
}
