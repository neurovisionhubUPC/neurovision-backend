package com.neurovision.rehab.iam.application.internal.commandservices;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.neurovision.rehab.iam.application.ports.out.persistence.DoctorInvitationRepository;
import com.neurovision.rehab.iam.domain.model.aggregates.DoctorInvitation;
import com.neurovision.rehab.iam.domain.model.commands.ActivateDoctorInvitationCommand;
import com.neurovision.rehab.iam.domain.model.valueobjects.CognitoSubject;
import com.neurovision.rehab.iam.domain.model.valueobjects.EmailAddress;
import com.neurovision.rehab.iam.domain.model.valueobjects.InvitationId;
import com.neurovision.rehab.iam.domain.model.valueobjects.InvitationStatus;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ActivateDoctorInvitationCommandServiceTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-12T11:00:00Z");
    private static final Instant AUTHENTICATED_AT = CREATED_AT.plusSeconds(60);
    private static final CognitoSubject DOCTOR = new CognitoSubject("doctor-sub");

    @Test
    void shouldActivateTheInvitationBelongingToTheAuthenticatedDoctor() {
        var repository = new SingleDoctorInvitationRepository(invitedInvitation());
        var service = new ActivateDoctorInvitationCommandService(repository);

        var invitation = service.handle(new ActivateDoctorInvitationCommand(DOCTOR, AUTHENTICATED_AT));

        assertEquals(InvitationStatus.ACTIVATED, invitation.status());
        assertEquals(AUTHENTICATED_AT, invitation.activatedAt().orElseThrow());
        assertEquals(1, repository.saveCount);
    }

    @Test
    void shouldReturnAnAlreadyActivatedInvitationWithoutSavingAgain() {
        var invitation = invitedInvitation();
        invitation.markActivated(AUTHENTICATED_AT);
        var repository = new SingleDoctorInvitationRepository(invitation);
        var service = new ActivateDoctorInvitationCommandService(repository);

        var result = service.handle(
                new ActivateDoctorInvitationCommand(DOCTOR, AUTHENTICATED_AT.plusSeconds(30)));

        assertEquals(AUTHENTICATED_AT, result.activatedAt().orElseThrow());
        assertEquals(0, repository.saveCount);
    }

    @Test
    void shouldRejectAnUnknownDoctorSubject() {
        var service = new ActivateDoctorInvitationCommandService(
                new SingleDoctorInvitationRepository(null));

        assertThrows(
                DoctorInvitationNotFoundException.class,
                () -> service.handle(new ActivateDoctorInvitationCommand(DOCTOR, AUTHENTICATED_AT)));
    }

    private static DoctorInvitation invitedInvitation() {
        var invitation = DoctorInvitation.initiate(
                new InvitationId(UUID.fromString("d7cacbf1-3d72-4ad7-b4e7-cd3e40734b67")),
                new CognitoSubject("administrator-sub"),
                new EmailAddress("doctor@example.com"),
                CREATED_AT);
        invitation.markInvited(DOCTOR, CREATED_AT.plusSeconds(30));
        invitation.pullDomainEvents();
        return invitation;
    }

    private static final class SingleDoctorInvitationRepository
            implements DoctorInvitationRepository {

        private DoctorInvitation invitation;
        private int saveCount;

        private SingleDoctorInvitationRepository(DoctorInvitation invitation) {
            this.invitation = invitation;
        }

        @Override
        public boolean existsActiveByDoctorEmail(EmailAddress doctorEmail) {
            return false;
        }

        @Override
        public Optional<DoctorInvitation> findById(InvitationId invitationId) {
            return Optional.ofNullable(invitation)
                    .filter(current -> current.id().equals(invitationId));
        }

        @Override
        public Optional<DoctorInvitation> findByDoctorSubject(CognitoSubject doctorSubject) {
            return Optional.ofNullable(invitation)
                    .filter(current -> current.doctorSubject()
                            .filter(doctorSubject::equals)
                            .isPresent());
        }

        @Override
        public void save(DoctorInvitation invitation) {
            this.invitation = invitation;
            saveCount++;
        }
    }
}
