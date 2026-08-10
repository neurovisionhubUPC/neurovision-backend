package com.neurovision.rehab.iam.infrastructure.messaging.outbox;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OutboxEventStore {

    List<ClaimedOutboxEvent> claimPending(
            String owner, int batchSize, Instant now, Instant lockedUntil);

    void markPublished(UUID eventId, String owner, Instant publishedAt);

    void recordFailure(UUID eventId, String owner, String error, Instant nextAttemptAt);
}
