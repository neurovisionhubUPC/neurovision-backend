package com.neurovision.rehab.iam.infrastructure.persistence.jpa.repositories;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.neurovision.rehab.iam.application.ports.out.messaging.PatientAccountInvitedV1;
import com.neurovision.rehab.iam.application.internal.commandservices.PatientAlreadyInvitedException;
import com.neurovision.rehab.iam.domain.model.aggregates.PatientInvitation;
import com.neurovision.rehab.iam.domain.model.valueobjects.CognitoSubject;
import com.neurovision.rehab.iam.domain.model.valueobjects.EmailAddress;
import com.neurovision.rehab.iam.domain.model.valueobjects.InvitationId;
import com.neurovision.rehab.iam.domain.model.valueobjects.InvitationStatus;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class JpaPatientInvitationRepositoryTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-10T10:00:00Z");

    @Inject
    JpaPatientInvitationRepository repository;

    @Inject
    PatientInvitationPanacheRepository invitationEntities;

    @Inject
    OutboxEventPanacheRepository outboxEvents;

    @BeforeEach
    @AfterEach
    void cleanDatabase() {
        QuarkusTransaction.requiringNew().run(() -> {
            outboxEvents.deleteAll();
            invitationEntities.deleteAll();
        });
    }

    @Test
    void shouldPersistAndRehydratePendingInvitation() {
        var invitation = pendingInvitation(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                "patient@example.com");

        repository.save(invitation);

        var restored = repository.findById(invitation.id()).orElseThrow();
        assertEquals(invitation.id(), restored.id());
        assertEquals(invitation.invitedByDoctor(), restored.invitedByDoctor());
        assertEquals(invitation.patientEmail(), restored.patientEmail());
        assertEquals(InvitationStatus.PENDING, restored.status());
        assertEquals(CREATED_AT, restored.createdAt());
        assertEquals(CREATED_AT, restored.updatedAt());
        assertTrue(restored.patientSubject().isEmpty());
        assertTrue(restored.failureCode().isEmpty());
        assertTrue(repository.existsActiveByPatientEmail(invitation.patientEmail()));
    }

    @Test
    void shouldAtomicallyPersistSentInvitationAndOutboxEvent() {
        var invitation = pendingInvitation(
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                "patient@example.com");
        repository.save(invitation);

        var sentAt = CREATED_AT.plusSeconds(30);
        invitation.markInvited(new CognitoSubject("patient-cognito-sub"), sentAt);
        var events = invitation.pullDomainEvents().stream()
                .map(PatientAccountInvitedV1::from)
                .toList();

        repository.saveWithOutboxEvents(invitation, events);

        var restored = repository.findById(invitation.id()).orElseThrow();
        assertEquals(InvitationStatus.INVITED, restored.status());
        assertEquals("patient-cognito-sub", restored.patientSubject().orElseThrow().value());
        assertEquals(sentAt, restored.updatedAt());

        var storedEvents = outboxEvents.listAll();
        assertEquals(1, storedEvents.size());
        assertEquals(events.getFirst().eventId(), storedEvents.getFirst().id());
        assertEquals(
                "patient-cognito-sub",
                storedEvents.getFirst().payload().get("patientSubject").asText());
        assertEquals(
                invitation.id().value().toString(),
                storedEvents.getFirst().payload().get("invitationId").asText());
    }

    @Test
    void shouldEnforceOnlyOneActiveInvitationPerPatientEmail() {
        var first = pendingInvitation(
                UUID.fromString("33333333-3333-3333-3333-333333333333"),
                "same-patient@example.com");
        var second = pendingInvitation(
                UUID.fromString("44444444-4444-4444-4444-444444444444"),
                "same-patient@example.com");
        repository.save(first);

        assertThrows(PatientAlreadyInvitedException.class, () -> repository.save(second));

        first.markFailed("PROVISIONING_FAILED", CREATED_AT.plusSeconds(30));
        repository.save(first);
        repository.save(second);

        assertFalse(repository.findById(first.id()).orElseThrow().failureCode().isEmpty());
        assertEquals(InvitationStatus.PENDING, repository.findById(second.id()).orElseThrow().status());
    }

    private static PatientInvitation pendingInvitation(UUID id, String patientEmail) {
        return PatientInvitation.initiate(
                new InvitationId(id),
                new CognitoSubject("doctor-cognito-sub"),
                new EmailAddress(patientEmail),
                CREATED_AT);
    }
}
