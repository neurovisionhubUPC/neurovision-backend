package com.neurovision.rehab.iam.infrastructure.persistence.jpa.repositories;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.neurovision.rehab.iam.application.ports.out.messaging.PatientAccountInvitedV1;
import com.neurovision.rehab.iam.domain.model.aggregates.PatientInvitation;
import com.neurovision.rehab.iam.domain.model.valueobjects.CognitoSubject;
import com.neurovision.rehab.iam.domain.model.valueobjects.EmailAddress;
import com.neurovision.rehab.iam.domain.model.valueobjects.InvitationId;
import com.neurovision.rehab.iam.infrastructure.persistence.jpa.entities.OutboxEventEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class JpaOutboxEventStoreTest {

    private static final Instant NOW = Instant.parse("2026-08-11T15:00:00Z");

    @Inject
    JpaPatientInvitationRepository invitationRepository;

    @Inject
    JpaOutboxEventStore outboxStore;

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
    void shouldLeaseRetryAndPublishWithoutConcurrentClaims() {
        var eventId = persistOutboxEvent();

        var firstClaim = outboxStore.claimPending("worker-a", 10, NOW, NOW.plusSeconds(60));
        assertEquals(1, firstClaim.size());
        assertEquals(1, firstClaim.getFirst().publishAttempts());
        assertTrue(outboxStore.claimPending("worker-b", 10, NOW, NOW.plusSeconds(60)).isEmpty());

        outboxStore.recordFailure(eventId, "worker-a", "Kafka unavailable", NOW.plusSeconds(5));
        var failed = reload(eventId);
        assertEquals(1, failed.publishAttempts());
        assertEquals("Kafka unavailable", failed.lastError());
        assertNull(failed.lockedBy());
        assertTrue(outboxStore
                .claimPending("worker-b", 10, NOW.plusSeconds(4), NOW.plusSeconds(64))
                .isEmpty());

        var retry = outboxStore.claimPending(
                "worker-b", 10, NOW.plusSeconds(5), NOW.plusSeconds(65));
        assertEquals(1, retry.size());
        assertEquals(2, retry.getFirst().publishAttempts());

        var publishedAt = NOW.plusSeconds(6);
        outboxStore.markPublished(eventId, "worker-b", publishedAt);
        var published = reload(eventId);
        assertEquals(publishedAt, published.publishedAt());
        assertNull(published.lockedBy());
        assertNull(published.lastError());
        assertTrue(outboxStore
                .claimPending("worker-c", 10, NOW.plusSeconds(120), NOW.plusSeconds(180))
                .isEmpty());
    }

    private UUID persistOutboxEvent() {
        var invitation = PatientInvitation.initiate(
                new InvitationId(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")),
                new CognitoSubject("doctor-sub"),
                new EmailAddress("patient@example.com"),
                NOW.minusSeconds(60));
        invitationRepository.save(invitation);
        invitation.markSent(new CognitoSubject("patient-sub"), NOW.minusSeconds(30));
        var event = PatientAccountInvitedV1.from(invitation.pullDomainEvents().getFirst());
        invitationRepository.saveWithOutboxEvents(invitation, java.util.List.of(event));
        return event.eventId();
    }

    private OutboxEventEntity reload(UUID eventId) {
        return QuarkusTransaction.requiringNew().call(() -> {
            outboxEvents.getEntityManager().clear();
            return outboxEvents.findById(eventId);
        });
    }
}
