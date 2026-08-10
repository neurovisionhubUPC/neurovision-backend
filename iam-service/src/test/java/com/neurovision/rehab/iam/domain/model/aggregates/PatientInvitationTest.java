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

        invitation.markSent(patientSubject, sentAt);

        assertEquals(InvitationStatus.SENT, invitation.status());
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
        invitation.markSent(
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

    private static PatientInvitation newInvitation() {
        return PatientInvitation.initiate(
                new InvitationId(UUID.fromString("f35da653-f9d6-4a58-8f1d-46900b3bf79c")),
                DOCTOR_SUBJECT,
                PATIENT_EMAIL,
                CREATED_AT);
    }
}
