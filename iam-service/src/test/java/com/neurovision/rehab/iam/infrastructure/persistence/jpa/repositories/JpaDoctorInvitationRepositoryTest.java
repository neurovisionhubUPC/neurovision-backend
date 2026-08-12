package com.neurovision.rehab.iam.infrastructure.persistence.jpa.repositories;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.neurovision.rehab.iam.application.internal.commandservices.DoctorAlreadyInvitedException;
import com.neurovision.rehab.iam.application.ports.out.messaging.DoctorAccountInvitedV1;
import com.neurovision.rehab.iam.domain.model.aggregates.DoctorInvitation;
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
class JpaDoctorInvitationRepositoryTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-11T10:00:00Z");

    @Inject
    JpaDoctorInvitationRepository repository;

    @Inject
    DoctorInvitationPanacheRepository invitationEntities;

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
                UUID.fromString("61111111-1111-1111-1111-111111111111"),
                "doctor@example.com");

        repository.save(invitation);

        var restored = repository.findById(invitation.id()).orElseThrow();
        assertEquals(invitation.id(), restored.id());
        assertEquals(invitation.invitedByAdministrator(), restored.invitedByAdministrator());
        assertEquals(invitation.doctorEmail(), restored.doctorEmail());
        assertEquals(InvitationStatus.PENDING, restored.status());
        assertEquals(CREATED_AT, restored.createdAt());
        assertTrue(restored.doctorSubject().isEmpty());
        assertTrue(repository.existsActiveByDoctorEmail(invitation.doctorEmail()));
    }

    @Test
    void shouldAtomicallyPersistInvitedDoctorAndOutboxEvent() {
        var invitation = pendingInvitation(
                UUID.fromString("62222222-2222-2222-2222-222222222222"),
                "doctor@example.com");
        repository.save(invitation);

        var invitedAt = CREATED_AT.plusSeconds(30);
        invitation.markInvited(new CognitoSubject("doctor-cognito-sub"), invitedAt);
        var events = invitation.pullDomainEvents().stream()
                .map(DoctorAccountInvitedV1::from)
                .toList();

        repository.saveAtomically(invitation, events);

        var restored = repository.findById(invitation.id()).orElseThrow();
        assertEquals(InvitationStatus.INVITED, restored.status());
        assertEquals("doctor-cognito-sub", restored.doctorSubject().orElseThrow().value());
        assertEquals(invitedAt, restored.updatedAt());

        var storedEvents = outboxEvents.listAll();
        assertEquals(1, storedEvents.size());
        assertEquals(events.getFirst().eventId(), storedEvents.getFirst().id());
        assertEquals(
                "doctor-cognito-sub",
                storedEvents.getFirst().payload().get("doctorSubject").asText());
        assertEquals(
                "admin-cognito-sub",
                storedEvents.getFirst().payload().get("invitedByAdministrator").asText());
    }

    @Test
    void shouldFindAndPersistActivationByDoctorSubject() {
        var invitation = pendingInvitation(
                UUID.fromString("63333333-3333-3333-3333-333333333333"),
                "activated-doctor@example.com");
        invitation.markInvited(
                new CognitoSubject("activated-doctor-sub"), CREATED_AT.plusSeconds(30));
        invitation.pullDomainEvents();
        repository.save(invitation);

        var restored = repository
                .findByDoctorSubject(new CognitoSubject("activated-doctor-sub"))
                .orElseThrow();
        var activatedAt = CREATED_AT.plusSeconds(60);
        restored.markActivated(activatedAt);
        repository.save(restored);

        var activated = QuarkusTransaction.requiringNew().call(() -> {
            invitationEntities.getEntityManager().clear();
            return repository.findById(invitation.id()).orElseThrow();
        });
        assertEquals(InvitationStatus.ACTIVATED, activated.status());
        assertEquals(activatedAt, activated.activatedAt().orElseThrow());
        assertTrue(repository
                .findByDoctorSubject(new CognitoSubject("unknown-doctor-sub"))
                .isEmpty());
    }

    @Test
    void shouldEnforceOnlyOneActiveInvitationPerDoctorEmail() {
        var first = pendingInvitation(
                UUID.fromString("64444444-4444-4444-4444-444444444444"),
                "same-doctor@example.com");
        var second = pendingInvitation(
                UUID.fromString("65555555-5555-5555-5555-555555555555"),
                "same-doctor@example.com");
        repository.save(first);

        assertThrows(DoctorAlreadyInvitedException.class, () -> repository.save(second));

        first.markFailed("PROVISIONING_FAILED", CREATED_AT.plusSeconds(30));
        repository.save(first);
        repository.save(second);

        assertFalse(repository.findById(first.id()).orElseThrow().failureCode().isEmpty());
        assertEquals(InvitationStatus.PENDING, repository.findById(second.id()).orElseThrow().status());
    }

    private static DoctorInvitation pendingInvitation(UUID id, String doctorEmail) {
        return DoctorInvitation.initiate(
                new InvitationId(id),
                new CognitoSubject("admin-cognito-sub"),
                new EmailAddress(doctorEmail),
                CREATED_AT);
    }
}
