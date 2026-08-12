package com.neurovision.rehab.iam.domain.model.aggregates;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.neurovision.rehab.iam.domain.model.valueobjects.CognitoSubject;
import com.neurovision.rehab.iam.domain.model.valueobjects.EmailAddress;
import com.neurovision.rehab.iam.domain.model.valueobjects.InvitationId;
import com.neurovision.rehab.iam.domain.model.valueobjects.InvitationStatus;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PatientInvitationTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-10T10:00:00Z");
    private static final CognitoSubject DOCTOR_SUBJECT =
            new CognitoSubject("8f77925d-ecc0-46f7-9d52-1aa2e4190f92");
    private static final EmailAddress PATIENT_EMAIL = new EmailAddress("patient@example.com");

    @Test
    void shouldCreateAPendingInvitation() {
        var invitation = newInvitation();

        assertEquals(InvitationStatus.PENDING, invitation.status());
        assertEquals(DOCTOR_SUBJECT, invitation.invitedByDoctor());
        assertEquals(PATIENT_EMAIL, invitation.patientEmail());
        assertEquals(CREATED_AT, invitation.createdAt());
        assertEquals(CREATED_AT, invitation.updatedAt());
        assertTrue(invitation.patientSubject().isEmpty());
        assertTrue(invitation.failureCode().isEmpty());
        assertTrue(invitation.pullDomainEvents().isEmpty());
    }

    @Test
    void shouldMarkTheInvitationAsSentAndRaiseOneDomainEvent() {
        var invitation = newInvitation();
        var patientSubject = new CognitoSubject("d4d1b886-6808-41cf-bc36-0601ef69f7bb");
        var sentAt = CREATED_AT.plusSeconds(30);

        invitation.markInvited(patientSubject, sentAt);

        assertEquals(InvitationStatus.INVITED, invitation.status());
        assertEquals(patientSubject, invitation.patientSubject().orElseThrow());
        assertEquals(sentAt, invitation.updatedAt());

        var events = invitation.pullDomainEvents();
        assertEquals(1, events.size());
        assertEquals(invitation.id(), events.getFirst().invitationId());
        assertEquals(DOCTOR_SUBJECT, events.getFirst().invitedByDoctor());
        assertEquals(patientSubject, events.getFirst().patientSubject());
        assertEquals(sentAt, events.getFirst().occurredAt());
        assertTrue(invitation.pullDomainEvents().isEmpty());
    }

    @Test
    void shouldMarkAPendingInvitationAsFailedWithoutRaisingAnEvent() {
        var invitation = newInvitation();
        var failedAt = CREATED_AT.plusSeconds(15);

        invitation.markFailed("COGNITO_ACCOUNT_CREATION_FAILED", failedAt);

        assertEquals(InvitationStatus.FAILED, invitation.status());
        assertEquals("COGNITO_ACCOUNT_CREATION_FAILED", invitation.failureCode().orElseThrow());
        assertEquals(failedAt, invitation.updatedAt());
        assertTrue(invitation.pullDomainEvents().isEmpty());
    }

    @Test
    void shouldRejectASecondTerminalTransition() {
        var invitation = newInvitation();
        invitation.markInvited(
                new CognitoSubject("d4d1b886-6808-41cf-bc36-0601ef69f7bb"),
                CREATED_AT.plusSeconds(30));

        assertThrows(
                IllegalStateException.class,
                () -> invitation.markFailed("UNEXPECTED_FAILURE", CREATED_AT.plusSeconds(60)));
    }

    @Test
    void shouldRejectATransitionBeforeTheLastRecordedTime() {
        var invitation = newInvitation();

        assertThrows(
                IllegalArgumentException.class,
                () -> invitation.markFailed("CLOCK_REGRESSION", CREATED_AT.minusSeconds(1)));
        assertFalse(invitation.failureCode().isPresent());
        assertEquals(InvitationStatus.PENDING, invitation.status());
    }

    @Test
    void shouldRehydrateAnInvitedPatientWithoutRaisingANewEvent() {
        var patientSubject = new CognitoSubject("d4d1b886-6808-41cf-bc36-0601ef69f7bb");
        var sentAt = CREATED_AT.plusSeconds(30);

        var invitation = PatientInvitation.rehydrate(
                new InvitationId(UUID.fromString("f35da653-f9d6-4a58-8f1d-46900b3bf79c")),
                DOCTOR_SUBJECT,
                PATIENT_EMAIL,
                InvitationStatus.INVITED,
                patientSubject,
                null,
                null,
                CREATED_AT,
                sentAt);

        assertEquals(InvitationStatus.INVITED, invitation.status());
        assertEquals(patientSubject, invitation.patientSubject().orElseThrow());
        assertEquals(sentAt, invitation.updatedAt());
        assertTrue(invitation.pullDomainEvents().isEmpty());
    }

    @Test
    void shouldRejectAnInconsistentRehydratedState() {
        assertThrows(
                IllegalArgumentException.class,
                () -> PatientInvitation.rehydrate(
                        new InvitationId(UUID.randomUUID()),
                        DOCTOR_SUBJECT,
                        PATIENT_EMAIL,
                        InvitationStatus.INVITED,
                        null,
                        null,
                        null,
                        CREATED_AT,
                        CREATED_AT));
    }

    @Test
    void shouldActivateAnInvitedPatientAccount() {
        var invitation = newInvitation();
        invitation.markInvited(
                new CognitoSubject("d4d1b886-6808-41cf-bc36-0601ef69f7bb"),
                CREATED_AT.plusSeconds(30));
        var activatedAt = CREATED_AT.plusSeconds(60);

        invitation.markActivated(activatedAt);

        assertEquals(InvitationStatus.ACTIVATED, invitation.status());
        assertEquals(activatedAt, invitation.activatedAt().orElseThrow());
        assertEquals(activatedAt, invitation.updatedAt());
    }

    @Test
    void shouldRejectActivationBeforeInvitation() {
        var invitation = newInvitation();

        assertThrows(
                IllegalStateException.class,
                () -> invitation.markActivated(CREATED_AT.plusSeconds(10)));
    }

    @Test
    void shouldRejectActivationTimeOutsidePersistedLifecycle() {
        assertThrows(
                IllegalArgumentException.class,
                () -> PatientInvitation.rehydrate(
                        new InvitationId(UUID.randomUUID()),
                        DOCTOR_SUBJECT,
                        PATIENT_EMAIL,
                        InvitationStatus.ACTIVATED,
                        new CognitoSubject("patient-sub"),
                        null,
                        CREATED_AT.plusSeconds(61),
                        CREATED_AT,
                        CREATED_AT.plusSeconds(60)));
    }

    private static PatientInvitation newInvitation() {
        return PatientInvitation.initiate(
                new InvitationId(UUID.fromString("f35da653-f9d6-4a58-8f1d-46900b3bf79c")),
                DOCTOR_SUBJECT,
                PATIENT_EMAIL,
                CREATED_AT);
    }
}
