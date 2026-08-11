package com.neurovision.rehab.profile.application.ports.out.persistence;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ProcessedInboxEvent(
        UUID eventId,
        String eventType,
        String eventVersion,
        Instant occurredAt,
        Instant processedAt) {

    public ProcessedInboxEvent {
        Objects.requireNonNull(eventId, "Event ID must not be null");
        eventType = requireText(eventType, "Event type must not be blank");
        eventVersion = requireText(eventVersion, "Event version must not be blank");
        Objects.requireNonNull(occurredAt, "Occurrence time must not be null");
        Objects.requireNonNull(processedAt, "Processing time must not be null");
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
