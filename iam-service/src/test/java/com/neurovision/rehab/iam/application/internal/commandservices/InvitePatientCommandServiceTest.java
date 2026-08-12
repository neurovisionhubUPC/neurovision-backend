package com.neurovision.rehab.iam.application.internal.commandservices;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.neurovision.rehab.iam.application.ports.out.identity.PatientIdentityProvider;
import com.neurovision.rehab.iam.application.ports.out.identity.ProvisionedPatientIdentity;
import com.neurovision.rehab.iam.application.ports.out.messaging.PatientAccountInvitedV1;
import com.neurovision.rehab.iam.application.ports.out.persistence.PatientInvitationRepository;
import com.neurovision.rehab.iam.application.internal.queryservices.PatientInvitationPage;
import com.neurovision.rehab.iam.domain.model.aggregates.PatientInvitation;
import com.neurovision.rehab.iam.domain.model.commands.InvitePatientCommand;
import com.neurovision.rehab.iam.domain.model.valueobjects.CognitoSubject;
import com.neurovision.rehab.iam.domain.model.valueobjects.EmailAddress;
import com.neurovision.rehab.iam.domain.model.valueobjects.InvitationStatus;
import com.neurovision.rehab.iam.domain.model.valueobjects.InvitationId;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class InvitePatientCommandServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-10T16:00:00Z");
    private static final EmailAddress PATIENT_EMAIL = new EmailAddress("patient@example.com");
    private static final CognitoSubject DOCTOR_SUBJECT =
            new CognitoSubject("8f77925d-ecc0-46f7-9d52-1aa2e4190f92");
    private static final CognitoSubject PATIENT_SUBJECT =
            new CognitoSubject("d4d1b886-6808-41cf-bc36-0601ef69f7bb");

    @Test
    void shouldProvisionPersistAndScheduleTheInvitationEvent() {
        var repository = new FakeRepository();
        var identityProvider = new FakeIdentityProvider();
        var service = service(repository, identityProvider);

        PatientInvitation result = service.handle(new InvitePatientCommand(PATIENT_EMAIL, DOCTOR_SUBJECT));

        assertEquals(InvitationStatus.INVITED, result.status());
        assertEquals(PATIENT_SUBJECT, result.patientSubject().orElseThrow());
        assertEquals(List.of(InvitationStatus.PENDING, InvitationStatus.INVITED), repository.savedStatuses);
        assertEquals(List.of(PATIENT_EMAIL), identityProvider.requestedEmails);
        assertEquals(1, repository.outboxEvents.size());
        assertEquals(result.id().value(), repository.outboxEvents.getFirst().invitationId());
        assertEquals(PATIENT_SUBJECT.value(), repository.outboxEvents.getFirst().patientSubject());
        assertEquals(DOCTOR_SUBJECT.value(), repository.outboxEvents.getFirst().invitedByDoctor());
    }

    @Test
    void shouldRejectAnExistingActiveInvitationBeforeProvisioning() {
        var repository = new FakeRepository();
        repository.activeInvitationExists = true;
        var identityProvider = new FakeIdentityProvider();
        var service = service(repository, identityProvider);

        assertThrows(
                PatientAlreadyInvitedException.class,
                () -> service.handle(new InvitePatientCommand(PATIENT_EMAIL, DOCTOR_SUBJECT)));

        assertTrue(repository.savedStatuses.isEmpty());
        assertTrue(identityProvider.requestedEmails.isEmpty());
        assertTrue(repository.outboxEvents.isEmpty());
    }

    @Test
    void shouldPersistAFailedInvitationWhenIdentityProvisioningFails() {
        var repository = new FakeRepository();
        var identityProvider = new FakeIdentityProvider();
        identityProvider.failure = new RuntimeException("Cognito unavailable");
        var service = service(repository, identityProvider);

        var error = assertThrows(
                PatientInvitationProvisioningException.class,
                () -> service.handle(new InvitePatientCommand(PATIENT_EMAIL, DOCTOR_SUBJECT)));

        assertEquals("Unable to provision the patient identity", error.getMessage());
        assertEquals(List.of(InvitationStatus.PENDING, InvitationStatus.FAILED), repository.savedStatuses);
        assertTrue(repository.outboxEvents.isEmpty());
    }

    private static InvitePatientCommandService service(
            PatientInvitationRepository repository,
            PatientIdentityProvider identityProvider) {
        return new InvitePatientCommandService(
                repository,
                identityProvider,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static final class FakeRepository implements PatientInvitationRepository {
        private final List<InvitationStatus> savedStatuses = new ArrayList<>();
        private final List<PatientAccountInvitedV1> outboxEvents = new ArrayList<>();
        private boolean activeInvitationExists;

        @Override
        public boolean existsActiveByPatientEmail(EmailAddress patientEmail) {
            return activeInvitationExists;
        }

        @Override
        public Optional<PatientInvitation> findById(InvitationId invitationId) {
            return Optional.empty();
        }

        @Override
        public Optional<PatientInvitation> findByPatientSubject(CognitoSubject patientSubject) {
            return Optional.empty();
        }

        @Override
        public PatientInvitationPage findByDoctor(
                CognitoSubject doctorSubject, int page, int size, InvitationStatus status) {
            return new PatientInvitationPage(List.of(), page, size, 0, 0);
        }

        @Override
        public void save(PatientInvitation invitation) {
            savedStatuses.add(invitation.status());
        }

        @Override
        public void saveWithOutboxEvents(
                PatientInvitation invitation, List<PatientAccountInvitedV1> events) {
            savedStatuses.add(invitation.status());
            outboxEvents.addAll(events);
        }
    }

    private static final class FakeIdentityProvider implements PatientIdentityProvider {
        private final List<EmailAddress> requestedEmails = new ArrayList<>();
        private RuntimeException failure;

        @Override
        public ProvisionedPatientIdentity invitePatient(EmailAddress patientEmail) {
            requestedEmails.add(patientEmail);
            if (failure != null) {
                throw failure;
            }
            return new ProvisionedPatientIdentity(PATIENT_SUBJECT);
        }
    }
}
