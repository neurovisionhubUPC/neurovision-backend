package com.neurovision.rehab.analytics.application.ports.out.messaging;

import java.util.concurrent.CompletionStage;

public interface AnalyticsDeadLetterPort {
    CompletionStage<Void> quarantine(FailedRecord record);

    record FailedRecord(String key, String payload, FailureCode code,
            String sourceTopic, int sourcePartition, long sourceOffset) {}

    enum FailureCode {
        INVALID_EVENT,
        BATCH_CONFLICT,
        SEQUENCE_CONFLICT,
        PERSISTENCE_RETRIES_EXHAUSTED
    }
}
