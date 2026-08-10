package com.neurovision.rehab.iam.infrastructure.persistence.jpa.repositories;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neurovision.rehab.iam.infrastructure.messaging.outbox.ClaimedOutboxEvent;
import com.neurovision.rehab.iam.infrastructure.messaging.outbox.OutboxEventStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@ApplicationScoped
public class JpaOutboxEventStore implements OutboxEventStore {

    private static final String CLAIM_SQL = """
            WITH candidates AS (
                SELECT id
                FROM outbox_events
                WHERE published_at IS NULL
                  AND next_attempt_at <= ?1
                  AND (locked_until IS NULL OR locked_until <= ?1)
                ORDER BY next_attempt_at, occurred_at, id
                FOR UPDATE SKIP LOCKED
                LIMIT ?2
            )
            UPDATE outbox_events event
            SET locked_by = ?3,
                locked_until = ?4,
                publish_attempts = publish_attempts + 1
            FROM candidates
            WHERE event.id = candidates.id
            RETURNING event.id,
                      event.aggregate_id,
                      event.event_type,
                      event.event_version,
                      event.payload::text,
                      event.occurred_at,
                      event.publish_attempts
            """;

    private final EntityManager entityManager;
    private final ObjectMapper objectMapper;

    public JpaOutboxEventStore(EntityManager entityManager, ObjectMapper objectMapper) {
        this.entityManager = Objects.requireNonNull(entityManager);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public List<ClaimedOutboxEvent> claimPending(
            String owner, int batchSize, Instant now, Instant lockedUntil) {
        validateClaim(owner, batchSize, now, lockedUntil);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery(CLAIM_SQL)
                .setParameter(1, now)
                .setParameter(2, batchSize)
                .setParameter(3, owner)
                .setParameter(4, lockedUntil)
                .getResultList();
        return rows.stream().map(this::toClaimedEvent).toList();
    }

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void markPublished(UUID eventId, String owner, Instant publishedAt) {
        int updated = entityManager.createQuery("""
                        update OutboxEventEntity event
                        set event.publishedAt = :publishedAt,
                            event.lastError = null,
                            event.lockedBy = null,
                            event.lockedUntil = null
                        where event.id = :eventId
                          and event.publishedAt is null
                          and event.lockedBy = :owner
                        """)
                .setParameter("publishedAt", Objects.requireNonNull(publishedAt))
                .setParameter("eventId", Objects.requireNonNull(eventId))
                .setParameter("owner", requireOwner(owner))
                .executeUpdate();
        requireOwnedUpdate(updated, eventId, owner);
    }

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void recordFailure(UUID eventId, String owner, String error, Instant nextAttemptAt) {
        if (error == null || error.isBlank()) {
            throw new IllegalArgumentException("Outbox error must not be blank");
        }
        int updated = entityManager.createQuery("""
                        update OutboxEventEntity event
                        set event.lastError = :error,
                            event.nextAttemptAt = :nextAttemptAt,
                            event.lockedBy = null,
                            event.lockedUntil = null
                        where event.id = :eventId
                          and event.publishedAt is null
                          and event.lockedBy = :owner
                        """)
                .setParameter("error", error)
                .setParameter("nextAttemptAt", Objects.requireNonNull(nextAttemptAt))
                .setParameter("eventId", Objects.requireNonNull(eventId))
                .setParameter("owner", requireOwner(owner))
                .executeUpdate();
        requireOwnedUpdate(updated, eventId, owner);
    }

    private ClaimedOutboxEvent toClaimedEvent(Object[] row) {
        try {
            return new ClaimedOutboxEvent(
                    (UUID) row[0],
                    (UUID) row[1],
                    (String) row[2],
                    ((Number) row[3]).shortValue(),
                    objectMapper.readTree((String) row[4]),
                    toInstant(row[5]),
                    ((Number) row[6]).intValue());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored outbox payload is not valid JSON", exception);
        }
    }

    private static Instant toInstant(Object value) {
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof OffsetDateTime offsetDateTime) {
            return offsetDateTime.toInstant();
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        throw new IllegalStateException("Unsupported outbox timestamp type: " + value.getClass());
    }

    private static void validateClaim(
            String owner, int batchSize, Instant now, Instant lockedUntil) {
        requireOwner(owner);
        if (batchSize < 1) {
            throw new IllegalArgumentException("Outbox batch size must be positive");
        }
        Objects.requireNonNull(now, "Claim time must not be null");
        Objects.requireNonNull(lockedUntil, "Lock expiration must not be null");
        if (!lockedUntil.isAfter(now)) {
            throw new IllegalArgumentException("Lock expiration must be after claim time");
        }
    }

    private static String requireOwner(String owner) {
        if (owner == null || owner.isBlank() || owner.length() > 128) {
            throw new IllegalArgumentException("Outbox owner must contain 1 to 128 characters");
        }
        return owner;
    }

    private static void requireOwnedUpdate(int updated, UUID eventId, String owner) {
        if (updated != 1) {
            throw new IllegalStateException(
                    "Outbox event %s is no longer leased by %s".formatted(eventId, owner));
        }
    }
}
