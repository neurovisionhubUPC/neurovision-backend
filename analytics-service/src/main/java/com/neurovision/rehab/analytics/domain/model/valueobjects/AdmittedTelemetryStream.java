package com.neurovision.rehab.analytics.domain.model.valueobjects;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record AdmittedTelemetryStream(
        TelemetryStreamIdentity identity,
        long admittedBatchCount,
        List<AdmittedTelemetryBatch> batches) {

    public AdmittedTelemetryStream {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(batches, "batches");
        batches = List.copyOf(batches);
        require(!batches.isEmpty(), "At least one admitted batch is required");
        require(admittedBatchCount == batches.size(), "admittedBatchCount must match reconstructed batches");

        var batchIds = new HashSet<UUID>();
        var sequences = new HashSet<Long>();
        for (var admitted : batches) {
            Objects.requireNonNull(admitted, "admitted batch");
            validateCompleteBatch(admitted.batch(), identity);
            require(batchIds.add(admitted.batch().batchId()), "Duplicate reconstructed batchId");
            require(sequences.add(admitted.batch().sequence()), "Duplicate reconstructed sequence");
        }
    }

    private static void validateCompleteBatch(AcceptedTelemetryBatch batch, TelemetryStreamIdentity identity) {
        Objects.requireNonNull(batch, "accepted batch");
        require(batch.schemaVersion() == 1, "Unsupported reconstructed schemaVersion");
        require(batch.sequence() >= 0, "Negative reconstructed sequence");
        Objects.requireNonNull(batch.eventId(), "eventId");
        Objects.requireNonNull(batch.batchId(), "batchId");
        Objects.requireNonNull(batch.therapySessionId(), "therapySessionId");
        Objects.requireNonNull(batch.streamId(), "streamId");
        Objects.requireNonNull(batch.capturedAt(), "capturedAt");
        Objects.requireNonNull(batch.sentAt(), "sentAt");
        Objects.requireNonNull(batch.serverReceivedAt(), "serverReceivedAt");
        Objects.requireNonNull(batch.correlationId(), "correlationId");
        Objects.requireNonNull(batch.gazeSamples(), "gazeSamples");
        Objects.requireNonNull(batch.gameplaySamples(), "gameplaySamples");
        require(!batch.capturedAt().isAfter(batch.sentAt()), "capturedAt exceeds sentAt");
        require(!batch.gazeSamples().isEmpty() || !batch.gameplaySamples().isEmpty(),
                "Reconstructed batch contains no samples");
        batch.gazeSamples().forEach(sample -> Objects.requireNonNull(sample, "gaze sample"));
        batch.gameplaySamples().forEach(sample -> Objects.requireNonNull(sample, "gameplay sample"));
        require(batch.payloadSha256() != null && !batch.payloadSha256().isBlank(),
                "payloadSha256 is required");
        require(identity.therapySessionId().equals(batch.therapySessionId()),
                "Reconstructed batch belongs to another therapy session");
        require(identity.streamId().equals(batch.streamId()),
                "Reconstructed batch belongs to another stream");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
