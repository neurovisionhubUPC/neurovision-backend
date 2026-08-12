package com.neurovision.rehab.iam.application.internal.commandservices;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.neurovision.rehab.iam.application.ports.out.persistence.PatientInvitationRepository;
import com.neurovision.rehab.iam.domain.model.aggregates.PatientInvitation;
import com.neurovision.rehab.iam.domain.model.commands.ActivatePatientInvitationCommand;
import com.neurovision.rehab.iam.domain.model.valueobjects.CognitoSubject;
import com.neurovision.rehab.iam.domain.model.valueobjects.EmailAddress;
import com.neurovision.rehab.iam.domain.model.valueobjects.InvitationId;
import com.neurovision.rehab.iam.domain.model.valueobjects.InvitationStatus;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ActivatePatientInvitationCommandServiceTest {

    private static final CognitoSubject PATIENT_SUBJECT = new CognitoSubject("patient-sub");
    private static final Instant INVITED_AT = Instant.parse("2026-08-11T14:00:00Z");
    private static final Instant AUTHENTICATED_AT = Instant.parse("2026-08-11T15:00:00Z");

    @Test
    void shouldActivateInvitationAtCognitoAuthenticationTime() {
        var repository = mock(PatientInvitationRepository.class);
        var invitation = invitedInvitation();
        when(repository.findByPatientSubject(PATIENT_SUBJECT)).thenReturn(Optional.of(invitation));
        var service = new ActivatePatientInvitationCommandService(repository);

        var result = service.handle(
                new ActivatePatientInvitationCommand(PATIENT_SUBJECT, AUTHENTICATED_AT));

        assertSame(invitation, result);
        assertEquals(InvitationStatus.ACTIVATED, result.status());
        assertEquals(AUTHENTICATED_AT, result.activatedAt().orElseThrow());
        verify(repository).save(invitation);
    }

    @Test
    void shouldFailWhenAuthenticatedPatientHasNoInvitation() {
        var repository = mock(PatientInvitationRepository.class);
        when(repository.findByPatientSubject(PATIENT_SUBJECT)).thenReturn(Optional.empty());
        var service = new ActivatePatientInvitationCommandService(repository);

        assertThrows(
                PatientInvitationNotFoundException.class,
                () -> service.handle(
                        new ActivatePatientInvitationCommand(PATIENT_SUBJECT, AUTHENTICATED_AT)));

        verify(repository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void shouldKeepOriginalActivationTimeWhenCalledAgain() {
        var repository = mock(PatientInvitationRepository.class);
        var invitation = invitedInvitation();
        invitation.markActivated(AUTHENTICATED_AT);
        when(repository.findByPatientSubject(PATIENT_SUBJECT)).thenReturn(Optional.of(invitation));
        var service = new ActivatePatientInvitationCommandService(repository);

        var result = service.handle(new ActivatePatientInvitationCommand(
                PATIENT_SUBJECT, AUTHENTICATED_AT.plusSeconds(3600)));

        assertEquals(AUTHENTICATED_AT, result.activatedAt().orElseThrow());
        verify(repository, never()).save(invitation);
    }

    private static PatientInvitation invitedInvitation() {
        var invitation = PatientInvitation.initiate(
                new InvitationId(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")),
                new CognitoSubject("doctor-sub"),
                new EmailAddress("patient@example.com"),
                INVITED_AT.minusSeconds(60));
        invitation.markInvited(PATIENT_SUBJECT, INVITED_AT);
        invitation.pullDomainEvents();
        return invitation;
    }
}
