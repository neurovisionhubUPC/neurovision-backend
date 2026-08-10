package com.neurovision.rehab.iam.infrastructure.messaging.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ClaimedOutboxEvent(
        UUID id,
        UUID aggregateId,
        String eventType,
        short eventVersion,
        JsonNode payload,
        Instant occurredAt,
        int publishAttempts) {

    public ClaimedOutboxEvent {
        Objects.requireNonNull(id, "Event ID must not be null");
        Objects.requireNonNull(aggregateId, "Aggregate ID must not be null");
        requireText(eventType, "Event type must not be blank");
        if (eventVersion < 1) {
            throw new IllegalArgumentException("Event version must be positive");
        }
        Objects.requireNonNull(payload, "Event payload must not be null");
        Objects.requireNonNull(occurredAt, "Occurrence time must not be null");
        if (publishAttempts < 1) {
            throw new IllegalArgumentException("Publish attempts must be positive after claiming");
        }
    }

    private static void requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
    }
}
