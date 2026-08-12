package com.neurovision.rehab.iam.domain.model.aggregates;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.neurovision.rehab.iam.domain.model.valueobjects.CognitoSubject;
import com.neurovision.rehab.iam.domain.model.valueobjects.EmailAddress;
import com.neurovision.rehab.iam.domain.model.valueobjects.InvitationId;
import com.neurovision.rehab.iam.domain.model.valueobjects.InvitationStatus;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DoctorInvitationTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-12T10:00:00Z");
    private static final CognitoSubject ADMINISTRATOR_SUBJECT =
            new CognitoSubject("administrator-cognito-sub");
    private static final EmailAddress DOCTOR_EMAIL = new EmailAddress("doctor@example.com");

    @Test
    void shouldInitiateAPendingDoctorInvitation() {
        var invitation = newInvitation();

        assertEquals(InvitationStatus.PENDING, invitation.status());
        assertEquals(ADMINISTRATOR_SUBJECT, invitation.invitedByAdministrator());
        assertEquals(DOCTOR_EMAIL, invitation.doctorEmail());
        assertEquals(CREATED_AT, invitation.createdAt());
        assertEquals(CREATED_AT, invitation.updatedAt());
        assertTrue(invitation.doctorSubject().isEmpty());
        assertTrue(invitation.failureCode().isEmpty());
        assertTrue(invitation.activatedAt().isEmpty());
        assertTrue(invitation.pullDomainEvents().isEmpty());
    }

    @Test
    void shouldMarkTheDoctorAsInvitedAndRaiseOneDomainEvent() {
        var invitation = newInvitation();
        var doctorSubject = new CognitoSubject("doctor-cognito-sub");
        var invitedAt = CREATED_AT.plusSeconds(30);

        invitation.markInvited(doctorSubject, invitedAt);

        assertEquals(InvitationStatus.INVITED, invitation.status());
        assertEquals(doctorSubject, invitation.doctorSubject().orElseThrow());
        assertEquals(invitedAt, invitation.updatedAt());

        var events = invitation.pullDomainEvents();
        assertEquals(1, events.size());
        assertEquals(invitation.id(), events.getFirst().invitationId());
        assertEquals(
                ADMINISTRATOR_SUBJECT, events.getFirst().invitedByAdministrator());
        assertEquals(doctorSubject, events.getFirst().doctorSubject());
        assertEquals(invitedAt, events.getFirst().occurredAt());
        assertTrue(invitation.pullDomainEvents().isEmpty());
    }

    @Test
    void shouldMarkAPendingDoctorInvitationAsFailed() {
        var invitation = newInvitation();
        var failedAt = CREATED_AT.plusSeconds(15);

        invitation.markFailed("COGNITO_DOCTOR_CREATION_FAILED", failedAt);

        assertEquals(InvitationStatus.FAILED, invitation.status());
        assertEquals("COGNITO_DOCTOR_CREATION_FAILED", invitation.failureCode().orElseThrow());
        assertEquals(failedAt, invitation.updatedAt());
        assertTrue(invitation.pullDomainEvents().isEmpty());
    }

    @Test
    void shouldRejectASecondProvisioningTransition() {
        var invitation = invitedInvitation();

        assertThrows(
                IllegalStateException.class,
                () -> invitation.markFailed("UNEXPECTED_FAILURE", CREATED_AT.plusSeconds(60)));
    }

    @Test
    void shouldRejectAProvisioningTransitionBeforeCreation() {
        var invitation = newInvitation();

        assertThrows(
                IllegalArgumentException.class,
                () -> invitation.markInvited(
                        new CognitoSubject("doctor-cognito-sub"), CREATED_AT.minusSeconds(1)));
        assertEquals(InvitationStatus.PENDING, invitation.status());
    }

    @Test
    void shouldActivateAnInvitedDoctorAccountIdempotently() {
        var invitation = invitedInvitation();
        var activatedAt = CREATED_AT.plusSeconds(60);

        invitation.markActivated(activatedAt);
        invitation.markActivated(activatedAt.plusSeconds(30));

        assertEquals(InvitationStatus.ACTIVATED, invitation.status());
        assertEquals(activatedAt, invitation.activatedAt().orElseThrow());
        assertEquals(activatedAt, invitation.updatedAt());
    }

    @Test
    void shouldRejectActivationBeforeTheDoctorIsInvited() {
        var invitation = newInvitation();

        assertThrows(
                IllegalStateException.class,
                () -> invitation.markActivated(CREATED_AT.plusSeconds(10)));
    }

    @Test
    void shouldRehydrateAnActivatedDoctorWithoutRaisingAnEvent() {
        var doctorSubject = new CognitoSubject("doctor-cognito-sub");
        var activatedAt = CREATED_AT.plusSeconds(60);

        var invitation = DoctorInvitation.rehydrate(
                invitationId(),
                ADMINISTRATOR_SUBJECT,
                DOCTOR_EMAIL,
                InvitationStatus.ACTIVATED,
                doctorSubject,
                null,
                activatedAt,
                CREATED_AT,
                activatedAt);

        assertEquals(InvitationStatus.ACTIVATED, invitation.status());
        assertEquals(doctorSubject, invitation.doctorSubject().orElseThrow());
        assertEquals(activatedAt, invitation.activatedAt().orElseThrow());
        assertTrue(invitation.pullDomainEvents().isEmpty());
    }

    @Test
    void shouldRejectInconsistentRehydratedStates() {
        assertThrows(
                IllegalArgumentException.class,
                () -> DoctorInvitation.rehydrate(
                        invitationId(),
                        ADMINISTRATOR_SUBJECT,
                        DOCTOR_EMAIL,
                        InvitationStatus.INVITED,
                        null,
                        null,
                        null,
                        CREATED_AT,
                        CREATED_AT));

        assertThrows(
                IllegalArgumentException.class,
                () -> DoctorInvitation.rehydrate(
                        invitationId(),
                        ADMINISTRATOR_SUBJECT,
                        DOCTOR_EMAIL,
                        InvitationStatus.FAILED,
                        new CognitoSubject("doctor-cognito-sub"),
                        "FAILURE",
                        null,
                        CREATED_AT,
                        CREATED_AT));
    }

    @Test
    void shouldRejectAnActivationTimeOutsideThePersistedLifecycle() {
        assertThrows(
                IllegalArgumentException.class,
                () -> DoctorInvitation.rehydrate(
                        invitationId(),
                        ADMINISTRATOR_SUBJECT,
                        DOCTOR_EMAIL,
                        InvitationStatus.ACTIVATED,
                        new CognitoSubject("doctor-cognito-sub"),
                        null,
                        CREATED_AT.plusSeconds(61),
                        CREATED_AT,
                        CREATED_AT.plusSeconds(60)));
    }

    private static DoctorInvitation invitedInvitation() {
        var invitation = newInvitation();
        invitation.markInvited(
                new CognitoSubject("doctor-cognito-sub"), CREATED_AT.plusSeconds(30));
        return invitation;
    }

    private static DoctorInvitation newInvitation() {
        return DoctorInvitation.initiate(
                invitationId(), ADMINISTRATOR_SUBJECT, DOCTOR_EMAIL, CREATED_AT);
    }

    private static InvitationId invitationId() {
        return new InvitationId(UUID.fromString("7ca4c568-e9cd-428a-a441-c8fa215c149f"));
    }
}
