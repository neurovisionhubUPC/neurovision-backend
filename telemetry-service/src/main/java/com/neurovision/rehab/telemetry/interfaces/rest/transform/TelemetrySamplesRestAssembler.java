package com.neurovision.rehab.telemetry.interfaces.rest.transform;

import com.neurovision.rehab.telemetry.application.ports.out.persistence.HistoricalTelemetryReadPort.Sample;
import com.neurovision.rehab.telemetry.interfaces.rest.resource.TelemetrySamplesPageV1.SampleV1;

public final class TelemetrySamplesRestAssembler {
    private TelemetrySamplesRestAssembler() {}

    public static SampleV1 toResponse(Sample sample) {
        return new SampleV1(sample.sampleTime(), sample.batchId(), sample.sequence(), sample.kind(),
                sample.index(), sample.offsetMs(), sample.serverReceivedAt(), sample.x(), sample.y(),
                sample.confidence(), sample.playerX(), sample.playerY(), sample.targetX(), sample.targetY(),
                sample.score(), sample.speed(), sample.progress());
    }
}
