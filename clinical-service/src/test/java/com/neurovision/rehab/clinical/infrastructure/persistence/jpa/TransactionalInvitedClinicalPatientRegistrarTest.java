package com.neurovision.rehab.clinical.infrastructure.persistence.jpa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.neurovision.rehab.clinical.application.internal.commandservices.RegisterInvitedClinicalPatientCommand;
import com.neurovision.rehab.clinical.application.internal.commandservices.RegisterInvitedClinicalPatientOutcome;
import com.neurovision.rehab.clinical.application.ports.out.persistence.ClinicalPatientRepository;
import com.neurovision.rehab.clinical.domain.model.valueobjects.ClinicalPatientStatus;
import com.neurovision.rehab.clinical.domain.model.valueobjects.CognitoSubject;
import com.neurovision.rehab.clinical.infrastructure.persistence.jpa.transaction.TransactionalInvitedClinicalPatientRegistrar;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class TransactionalInvitedClinicalPatientRegistrarTest {

    private static final String DOCTOR_SUBJECT = "7458e448-6051-7081-2745-186f682d626c";
    private static final String PATIENT_SUBJECT = "84d84438-f031-70ea-fd6e-3b4011bbb46e";
    private static final Instant OCCURRED_AT = Instant.parse("2026-08-11T04:29:12Z");

    @Inject
    TransactionalInvitedClinicalPatientRegistrar registrar;

    @Inject
    ClinicalPatientRepository clinicalPatients;

    @Inject
    EntityManager entityManager;

    @BeforeEach
    @AfterEach
    void cleanDatabase() {
        QuarkusTransaction.requiringNew().run(() -> {
            entityManager.createNativeQuery("DELETE FROM inbox_events").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM doctor_patient_assignments").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM clinical_patients").executeUpdate();
        });
    }

    @Test
    void shouldPersistPatientAssignmentAndInboxInOneOperation() {
        var result = registrar.handle(command(
                UUID.fromString("112e0134-5d78-47a4-965b-7572a54c47f4"),
                DOCTOR_SUBJECT,
                PATIENT_SUBJECT,
                OCCURRED_AT));

        assertEquals(RegisterInvitedClinicalPatientOutcome.REGISTERED, result);
        var patient = clinicalPatients
                .findByPatientSubject(new CognitoSubject(PATIENT_SUBJECT))
                .orElseThrow();
        assertEquals(ClinicalPatientStatus.ACTIVE, patient.status());
        assertTrue(patient.strokeDiagnosis().isEmpty());
        assertTrue(patient.sequelae().isEmpty());
        assertEquals(OCCURRED_AT, patient.createdAt());
        assertEquals(1L, countRows("clinical_patients"));
        assertEquals(1L, countRows("doctor_patient_assignments"));
        assertEquals(1L, countRows("inbox_events"));
    }

    @Test
    void shouldTreatTheSameEventAsAnIdempotentDuplicate() {
        var command = command(
                UUID.fromString("74e9c7b5-ce94-482b-bd82-e8d8b9b82535"),
                DOCTOR_SUBJECT,
                PATIENT_SUBJECT,
                OCCURRED_AT);

        assertEquals(RegisterInvitedClinicalPatientOutcome.REGISTERED, registrar.handle(command));
        assertEquals(RegisterInvitedClinicalPatientOutcome.DUPLICATE_EVENT, registrar.handle(command));

        assertEquals(1L, countRows("clinical_patients"));
        assertEquals(1L, countRows("doctor_patient_assignments"));
        assertEquals(1L, countRows("inbox_events"));
    }

    @Test
    void shouldRecordAnotherEventWithoutDuplicatingPatientOrAssignment() {
        registrar.handle(command(
                UUID.fromString("297b59e5-ed16-44db-85e6-b2865501cb24"),
                DOCTOR_SUBJECT,
                PATIENT_SUBJECT,
                OCCURRED_AT));

        var result = registrar.handle(command(
                UUID.fromString("f896394e-10d7-4b7a-9632-9fa99d8957d8"),
                DOCTOR_SUBJECT,
                PATIENT_SUBJECT,
                OCCURRED_AT.plusSeconds(1)));

        assertEquals(RegisterInvitedClinicalPatientOutcome.ALREADY_REGISTERED, result);
        assertEquals(1L, countRows("clinical_patients"));
        assertEquals(1L, countRows("doctor_patient_assignments"));
        assertEquals(2L, countRows("inbox_events"));
    }

    @Test
    void shouldCreateAMissingAssignmentForAnExistingPatient() {
        registrar.handle(command(
                UUID.fromString("0e8bcac4-4813-4759-a29b-26bb57d37f48"),
                DOCTOR_SUBJECT,
                PATIENT_SUBJECT,
                OCCURRED_AT));

        var result = registrar.handle(command(
                UUID.fromString("cc4588e0-ebd9-4f9f-88d4-791eab6e8fb9"),
                "second-doctor-sub",
                PATIENT_SUBJECT,
                OCCURRED_AT.plusSeconds(1)));

        assertEquals(RegisterInvitedClinicalPatientOutcome.ASSIGNMENT_CREATED, result);
        assertEquals(1L, countRows("clinical_patients"));
        assertEquals(2L, countRows("doctor_patient_assignments"));
        assertEquals(2L, countRows("inbox_events"));
    }

    @Test
    void shouldRollBackPatientAndAssignmentWhenInboxPersistenceFails() {
        var futureOccurrence = Instant.now().plus(1, ChronoUnit.DAYS);

        assertThrows(
                RuntimeException.class,
                () -> registrar.handle(command(
                        UUID.fromString("e0454328-cdd6-4777-80cc-8f0587491b55"),
                        "rollback-doctor-sub",
                        "rollback-patient-sub",
                        futureOccurrence)));

        assertTrue(clinicalPatients
                .findByPatientSubject(new CognitoSubject("rollback-patient-sub"))
                .isEmpty());
        assertEquals(0L, countRows("clinical_patients"));
        assertEquals(0L, countRows("doctor_patient_assignments"));
        assertEquals(0L, countRows("inbox_events"));
    }

    private static RegisterInvitedClinicalPatientCommand command(
            UUID eventId, String doctorSubject, String patientSubject, Instant occurredAt) {
        return new RegisterInvitedClinicalPatientCommand(
                eventId, doctorSubject, patientSubject, occurredAt);
    }

    private long countRows(String tableName) {
        return QuarkusTransaction.requiringNew().call(() -> ((Number) entityManager
                        .createNativeQuery("SELECT COUNT(*) FROM " + tableName)
                        .getSingleResult())
                .longValue());
    }
}
