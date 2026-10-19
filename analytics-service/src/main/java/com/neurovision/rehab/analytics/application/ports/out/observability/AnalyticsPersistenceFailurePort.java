package com.neurovision.rehab.analytics.application.ports.out.observability;

/** Best-effort observation; implementations must never export throwable text or identity. */
public interface AnalyticsPersistenceFailurePort {
    void record(Operation operation, Throwable failure);

    enum Operation {
        INBOX_PREPARE, INBOX_FIND_BATCH, INBOX_FIND_SEQUENCE, INBOX_MAX_SEQUENCE,
        INBOX_INSERT, STREAM_LOAD, PROJECTION_CALCULATE, PROJECTION_STORE, PIPELINE
    }
}
