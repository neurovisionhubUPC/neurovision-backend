package com.neurovision.rehab.telemetry.application.ports.out.messaging;

import java.util.concurrent.CompletionStage;

/** Broker acknowledgement is required before acknowledging the source record. */
public interface TelemetryDeadLetterPort {
    CompletionStage<Void> quarantine(FailedRecord record);

    record FailedRecord(String key, String payload, FailureCode code,
            String sourceTopic, int sourcePartition, long sourceOffset) {}

    enum FailureCode {
        INVALID_EVENT,
        PERSISTENCE_REJECTED,
        PERSISTENCE_RETRIES_EXHAUSTED
    }
}
