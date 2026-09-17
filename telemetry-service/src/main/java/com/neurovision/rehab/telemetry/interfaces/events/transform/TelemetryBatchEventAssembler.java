package com.neurovision.rehab.telemetry.interfaces.events.transform;

import com.neurovision.rehab.telemetry.application.ports.out.messaging.TelemetryPublicationPort.Publication;
import com.neurovision.rehab.telemetry.interfaces.events.contracts.TelemetryBatchAcceptedV1;

/** Maps an accepted application batch to the independent Kafka V1 contract. */
public final class TelemetryBatchEventAssembler {
    private TelemetryBatchEventAssembler() {}

    public static String partitionKey(Publication publication) {
        var batch = publication.validation().batch();
        return batch.therapySessionId() + ":" + batch.streamId();
    }

    public static TelemetryBatchAcceptedV1 toV1(Publication publication) {
        var batch = publication.validation().batch();
        return new TelemetryBatchAcceptedV1(
                1,
                java.util.UUID.randomUUID(),
                batch.batchId(),
                batch.therapySessionId(),
                batch.streamId(),
                batch.sequence(),
                batch.capturedAt(),
                batch.sentAt(),
                publication.validation().serverReceivedAt(),
                publication.correlationId(),
                publication.traceId(),
                batch.gazeSamples().stream().map(s ->
                        new TelemetryBatchAcceptedV1.GazeSampleV1(s.offsetMs(), s.x(), s.y(), s.confidence())).toList(),
                batch.gameplaySamples().stream().map(s ->
                        new TelemetryBatchAcceptedV1.GameplaySampleV1(s.offsetMs(), s.playerX(), s.playerY(),
                                s.targetX(), s.targetY(), s.score(), s.speed(), s.progress())).toList());
    }
}
