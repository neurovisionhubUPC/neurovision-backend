package com.neurovision.rehab.profile.infrastructure.persistence.jpa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.neurovision.rehab.profile.application.internal.commandservices.RegisterInvitedPatientCommand;
import com.neurovision.rehab.profile.application.internal.commandservices.RegisterInvitedPatientOutcome;
import com.neurovision.rehab.profile.application.ports.out.persistence.UserProfileRepository;
import com.neurovision.rehab.profile.domain.model.valueobjects.CognitoSubject;
import com.neurovision.rehab.profile.domain.model.valueobjects.ProfileStatus;
import com.neurovision.rehab.profile.domain.model.valueobjects.ProfileType;
import com.neurovision.rehab.profile.infrastructure.persistence.jpa.transaction.TransactionalInvitedPatientRegistrar;
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
class TransactionalInvitedPatientRegistrarTest {

    private static final String PATIENT_SUBJECT = "44b884b8-d0c1-70f2-c311-c56bb74764a9";
    private static final Instant OCCURRED_AT = Instant.parse("2026-08-10T21:04:30Z");

    @Inject
    TransactionalInvitedPatientRegistrar registrar;

    @Inject
    UserProfileRepository profiles;

    @Inject
    EntityManager entityManager;

    @BeforeEach
    @AfterEach
    void cleanDatabase() {
        QuarkusTransaction.requiringNew().run(() -> {
            entityManager.createNativeQuery("DELETE FROM inbox_events").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM user_profiles").executeUpdate();
        });
    }

    @Test
    void shouldPersistTheProfileAndInboxEventInOneOperation() {
        var result = registrar.handle(command(
                UUID.fromString("bc560471-7dd4-4dc4-9249-b18d6545a174"),
                PATIENT_SUBJECT,
                OCCURRED_AT));

        assertEquals(RegisterInvitedPatientOutcome.REGISTERED, result);
        var profile = profiles.findByCognitoSubject(new CognitoSubject(PATIENT_SUBJECT))
                .orElseThrow();
        assertEquals(ProfileType.PATIENT, profile.type());
        assertEquals(ProfileStatus.PENDING_COMPLETION, profile.status());
        assertEquals(OCCURRED_AT, profile.createdAt());
        assertEquals(1L, countRows("inbox_events"));
    }

    @Test
    void shouldTreatTheSameEventAsAnIdempotentDuplicate() {
        var eventId = UUID.fromString("74e9c7b5-ce94-482b-bd82-e8d8b9b82535");
        var command = command(eventId, PATIENT_SUBJECT, OCCURRED_AT);

        assertEquals(RegisterInvitedPatientOutcome.REGISTERED, registrar.handle(command));
        assertEquals(RegisterInvitedPatientOutcome.DUPLICATE_EVENT, registrar.handle(command));

        assertEquals(1L, countRows("user_profiles"));
        assertEquals(1L, countRows("inbox_events"));
    }

    @Test
    void shouldRecordAnotherEventWithoutDuplicatingThePatientProfile() {
        assertEquals(
                RegisterInvitedPatientOutcome.REGISTERED,
                registrar.handle(command(
                        UUID.fromString("297b59e5-ed16-44db-85e6-b2865501cb24"),
                        PATIENT_SUBJECT,
                        OCCURRED_AT)));

        var result = registrar.handle(command(
                UUID.fromString("f896394e-10d7-4b7a-9632-9fa99d8957d8"),
                PATIENT_SUBJECT,
                OCCURRED_AT.plusSeconds(1)));

        assertEquals(RegisterInvitedPatientOutcome.PROFILE_ALREADY_REGISTERED, result);
        assertEquals(1L, countRows("user_profiles"));
        assertEquals(2L, countRows("inbox_events"));
    }

    @Test
    void shouldRollBackTheProfileWhenInboxPersistenceFails() {
        var futureOccurrence = Instant.now().plus(1, ChronoUnit.DAYS);
        var subject = "rollback-patient-sub";

        assertThrows(
                RuntimeException.class,
                () -> registrar.handle(command(
                        UUID.fromString("e0454328-cdd6-4777-80cc-8f0587491b55"),
                        subject,
                        futureOccurrence)));

        assertTrue(profiles.findByCognitoSubject(new CognitoSubject(subject)).isEmpty());
        assertEquals(0L, countRows("inbox_events"));
    }

    private static RegisterInvitedPatientCommand command(
            UUID eventId, String patientSubject, Instant occurredAt) {
        return new RegisterInvitedPatientCommand(eventId, patientSubject, occurredAt);
    }

    private long countRows(String tableName) {
        return QuarkusTransaction.requiringNew().call(() -> ((Number) entityManager
                        .createNativeQuery("SELECT COUNT(*) FROM " + tableName)
                        .getSingleResult())
                .longValue());
    }
}
