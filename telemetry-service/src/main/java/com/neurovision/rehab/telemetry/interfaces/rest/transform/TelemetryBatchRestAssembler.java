package com.neurovision.rehab.telemetry.interfaces.rest.transform;

import java.util.List;

import com.neurovision.rehab.telemetry.domain.model.valueobjects.TelemetryBatch;
import com.neurovision.rehab.telemetry.domain.model.valueobjects.TelemetryBatch.GameplaySample;
import com.neurovision.rehab.telemetry.domain.model.valueobjects.TelemetryBatch.GazeSample;
import com.neurovision.rehab.telemetry.interfaces.rest.resource.TelemetryBatchV1;
import com.neurovision.rehab.telemetry.interfaces.rest.resource.TelemetryBatchV1.GameplaySampleV1;
import com.neurovision.rehab.telemetry.interfaces.rest.resource.TelemetryBatchV1.GazeSampleV1;

public final class TelemetryBatchRestAssembler {

    private TelemetryBatchRestAssembler() {}

    public static TelemetryBatch toDomain(TelemetryBatchV1 source) {
        if (source == null) {
            return null;
        }
        return new TelemetryBatch(
                source.schemaVersion(),
                source.batchId(),
                source.therapySessionId(),
                source.streamId(),
                source.sequence(),
                source.capturedAt(),
                source.sentAt(),
                mapGazeSamples(source.gazeSamples()),
                mapGameplaySamples(source.gameplaySamples()));
    }

    private static List<GazeSample> mapGazeSamples(List<GazeSampleV1> samples) {
        return samples == null
                ? null
                : samples.stream().map(sample -> sample == null
                        ? null
                        : new GazeSample(sample.offsetMs(), sample.x(), sample.y(), sample.confidence())).toList();
    }

    private static List<GameplaySample> mapGameplaySamples(List<GameplaySampleV1> samples) {
        return samples == null
                ? null
                : samples.stream().map(sample -> sample == null
                        ? null
                        : new GameplaySample(
                                sample.offsetMs(),
                                sample.playerX(),
                                sample.playerY(),
                                sample.targetX(),
                                sample.targetY(),
                                sample.score(),
                                sample.speed(),
                                sample.progress())).toList();
    }
}
