package com.neurovision.rehab.iam.application.internal.commandservices;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.neurovision.rehab.iam.application.ports.out.identity.PatientIdentityProvider;
import com.neurovision.rehab.iam.application.ports.out.identity.ProvisionedPatientIdentity;
import com.neurovision.rehab.iam.application.ports.out.messaging.PatientAccountEventPublisher;
import com.neurovision.rehab.iam.application.ports.out.messaging.PatientAccountInvitedV1;
import com.neurovision.rehab.iam.application.ports.out.persistence.PatientInvitationRepository;
import com.neurovision.rehab.iam.domain.model.aggregates.PatientInvitation;
import com.neurovision.rehab.iam.domain.model.commands.InvitePatientCommand;
import com.neurovision.rehab.iam.domain.model.valueobjects.CognitoSubject;
import com.neurovision.rehab.iam.domain.model.valueobjects.EmailAddress;
import com.neurovision.rehab.iam.domain.model.valueobjects.InvitationStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
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
        var eventPublisher = new FakeEventPublisher();
        var service = service(repository, identityProvider, eventPublisher);

        PatientInvitation result = service.handle(new InvitePatientCommand(PATIENT_EMAIL, DOCTOR_SUBJECT));

        assertEquals(InvitationStatus.SENT, result.status());
        assertEquals(PATIENT_SUBJECT, result.patientSubject().orElseThrow());
        assertEquals(List.of(InvitationStatus.PENDING, InvitationStatus.SENT), repository.savedStatuses);
        assertEquals(List.of(PATIENT_EMAIL), identityProvider.requestedEmails);
        assertEquals(1, eventPublisher.events.size());
        assertEquals(result.id().value(), eventPublisher.events.getFirst().invitationId());
        assertEquals(PATIENT_SUBJECT.value(), eventPublisher.events.getFirst().patientSubject());
        assertEquals(DOCTOR_SUBJECT.value(), eventPublisher.events.getFirst().invitedByDoctor());
    }

    @Test
    void shouldRejectAnExistingActiveInvitationBeforeProvisioning() {
        var repository = new FakeRepository();
        repository.activeInvitationExists = true;
        var identityProvider = new FakeIdentityProvider();
        var eventPublisher = new FakeEventPublisher();
        var service = service(repository, identityProvider, eventPublisher);

        assertThrows(
                PatientAlreadyInvitedException.class,
                () -> service.handle(new InvitePatientCommand(PATIENT_EMAIL, DOCTOR_SUBJECT)));

        assertTrue(repository.savedStatuses.isEmpty());
        assertTrue(identityProvider.requestedEmails.isEmpty());
        assertTrue(eventPublisher.events.isEmpty());
    }

    @Test
    void shouldPersistAFailedInvitationWhenIdentityProvisioningFails() {
        var repository = new FakeRepository();
        var identityProvider = new FakeIdentityProvider();
        identityProvider.failure = new RuntimeException("Cognito unavailable");
        var eventPublisher = new FakeEventPublisher();
        var service = service(repository, identityProvider, eventPublisher);

        var error = assertThrows(
                PatientInvitationProvisioningException.class,
                () -> service.handle(new InvitePatientCommand(PATIENT_EMAIL, DOCTOR_SUBJECT)));

        assertEquals("Unable to provision the patient identity", error.getMessage());
        assertEquals(List.of(InvitationStatus.PENDING, InvitationStatus.FAILED), repository.savedStatuses);
        assertTrue(eventPublisher.events.isEmpty());
    }

    private static InvitePatientCommandService service(
            PatientInvitationRepository repository,
            PatientIdentityProvider identityProvider,
            PatientAccountEventPublisher eventPublisher) {
        return new InvitePatientCommandService(
                repository,
                identityProvider,
                eventPublisher,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static final class FakeRepository implements PatientInvitationRepository {
        private final List<InvitationStatus> savedStatuses = new ArrayList<>();
        private boolean activeInvitationExists;

        @Override
        public boolean existsActiveByPatientEmail(EmailAddress patientEmail) {
            return activeInvitationExists;
        }

        @Override
        public void save(PatientInvitation invitation) {
            savedStatuses.add(invitation.status());
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

    private static final class FakeEventPublisher implements PatientAccountEventPublisher {
        private final List<PatientAccountInvitedV1> events = new ArrayList<>();

        @Override
        public void schedule(PatientAccountInvitedV1 event) {
            events.add(event);
        }
    }
}
