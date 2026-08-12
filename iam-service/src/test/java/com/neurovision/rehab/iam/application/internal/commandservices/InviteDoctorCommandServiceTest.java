package com.neurovision.rehab.iam.application.internal.commandservices;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.neurovision.rehab.iam.application.ports.out.identity.DoctorIdentityProvider;
import com.neurovision.rehab.iam.application.ports.out.identity.ProvisionedDoctorIdentity;
import com.neurovision.rehab.iam.application.ports.out.messaging.DoctorAccountInvitedV1;
import com.neurovision.rehab.iam.application.ports.out.messaging.DoctorInvitationOutbox;
import com.neurovision.rehab.iam.application.ports.out.persistence.DoctorInvitationRepository;
import com.neurovision.rehab.iam.domain.model.aggregates.DoctorInvitation;
import com.neurovision.rehab.iam.domain.model.commands.InviteDoctorCommand;
import com.neurovision.rehab.iam.domain.model.valueobjects.CognitoSubject;
import com.neurovision.rehab.iam.domain.model.valueobjects.EmailAddress;
import com.neurovision.rehab.iam.domain.model.valueobjects.InvitationId;
import com.neurovision.rehab.iam.domain.model.valueobjects.InvitationStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InviteDoctorCommandServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-12T11:00:00Z");
    private static final UUID INVITATION_ID =
            UUID.fromString("d7cacbf1-3d72-4ad7-b4e7-cd3e40734b67");
    private static final CognitoSubject ADMINISTRATOR =
            new CognitoSubject("administrator-sub");
    private static final CognitoSubject DOCTOR = new CognitoSubject("doctor-sub");
    private static final EmailAddress EMAIL = new EmailAddress("doctor@example.com");

    @Test
    void shouldProvisionTheDoctorAndSaveTheIntegrationEventAtomically() {
        var store = new InMemoryDoctorInvitationStore();
        var service = service(store, email -> new ProvisionedDoctorIdentity(DOCTOR));

        var invitation = service.handle(command());

        assertEquals(InvitationStatus.INVITED, invitation.status());
        assertEquals(DOCTOR, invitation.doctorSubject().orElseThrow());
        assertEquals(1, store.regularSaves.size());
        assertEquals(InvitationStatus.PENDING, store.regularSaves.getFirst());
        assertEquals(1, store.outboxEvents.size());
        var event = store.outboxEvents.getFirst();
        assertEquals(INVITATION_ID, event.invitationId());
        assertEquals(ADMINISTRATOR.value(), event.invitedByAdministrator());
        assertEquals(DOCTOR.value(), event.doctorSubject());
        assertEquals(NOW, event.occurredAt());
    }

    @Test
    void shouldRejectAnActiveInvitationForTheSameDoctorEmail() {
        var store = new InMemoryDoctorInvitationStore();
        store.activeEmailExists = true;
        var service = service(store, email -> new ProvisionedDoctorIdentity(DOCTOR));

        assertThrows(DoctorAlreadyInvitedException.class, () -> service.handle(command()));

        assertTrue(store.regularSaves.isEmpty());
        assertTrue(store.outboxEvents.isEmpty());
    }

    @Test
    void shouldPersistTheFailureAndPreserveTheProvisioningCause() {
        var store = new InMemoryDoctorInvitationStore();
        var cognitoFailure = new IllegalStateException("Cognito unavailable");
        var service = service(store, email -> {
            throw cognitoFailure;
        });

        var failure =
                assertThrows(DoctorInvitationProvisioningException.class, () -> service.handle(command()));

        assertEquals(cognitoFailure, failure.getCause());
        assertEquals(List.of(InvitationStatus.PENDING, InvitationStatus.FAILED), store.regularSaves);
        assertEquals(
                "IDENTITY_PROVISIONING_FAILED",
                store.invitation.failureCode().orElseThrow());
        assertTrue(store.outboxEvents.isEmpty());
    }

    @Test
    void shouldRejectANullCommand() {
        var service = service(
                new InMemoryDoctorInvitationStore(),
                email -> new ProvisionedDoctorIdentity(DOCTOR));

        assertThrows(NullPointerException.class, () -> service.handle(null));
    }

    private static InviteDoctorCommandService service(
            InMemoryDoctorInvitationStore store, DoctorIdentityProvider identityProvider) {
        return new InviteDoctorCommandService(
                store,
                store,
                identityProvider,
                () -> INVITATION_ID,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static InviteDoctorCommand command() {
        return new InviteDoctorCommand(EMAIL, ADMINISTRATOR);
    }

    private static final class InMemoryDoctorInvitationStore
            implements DoctorInvitationRepository, DoctorInvitationOutbox {

        private boolean activeEmailExists;
        private DoctorInvitation invitation;
        private final List<InvitationStatus> regularSaves = new ArrayList<>();
        private final List<DoctorAccountInvitedV1> outboxEvents = new ArrayList<>();

        @Override
        public boolean existsActiveByDoctorEmail(EmailAddress doctorEmail) {
            assertEquals(EMAIL, doctorEmail);
            return activeEmailExists;
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
            regularSaves.add(invitation.status());
        }

        @Override
        public void saveAtomically(
                DoctorInvitation invitation, List<DoctorAccountInvitedV1> events) {
            this.invitation = invitation;
            assertInstanceOf(DoctorInvitation.class, invitation);
            outboxEvents.addAll(events);
        }
    }
}
