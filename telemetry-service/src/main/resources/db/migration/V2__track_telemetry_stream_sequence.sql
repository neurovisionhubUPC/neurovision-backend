-- An ordinary table enforces sequence uniqueness independently of hypertable time.
-- An existing deployment with conflicting Phase 5 rows must be reconciled before
-- this migration; silently choosing a winner would corrupt clinical history.
CREATE UNIQUE INDEX telemetry_batch_inbox_stream_sequence_uq
    ON telemetry_batch_inbox (therapy_session_id, stream_id, sequence_number);

ALTER TABLE telemetry_batch_inbox
    ADD COLUMN sequence_quality VARCHAR(16) NOT NULL DEFAULT 'LEGACY',
    ADD COLUMN missing_sequence_start BIGINT,
    ADD COLUMN missing_sequence_end BIGINT,
    ADD CONSTRAINT telemetry_batch_inbox_quality_ck CHECK (
        sequence_quality IN ('LEGACY', 'INITIAL', 'IN_ORDER', 'GAP_DETECTED', 'LATE_ARRIVAL')
        AND ((sequence_quality = 'GAP_DETECTED'
            AND missing_sequence_start IS NOT NULL
            AND missing_sequence_end IS NOT NULL
            AND missing_sequence_start <= missing_sequence_end)
          OR (sequence_quality <> 'GAP_DETECTED'
            AND missing_sequence_start IS NULL AND missing_sequence_end IS NULL))
    );

-- One row per stream is locked during each claim to classify arrivals consistently
-- even if a replay or multiple consumers process the same stream concurrently.
CREATE TABLE telemetry_stream_progress (
    therapy_session_id UUID NOT NULL,
    stream_id UUID NOT NULL,
    highest_sequence_number BIGINT NOT NULL CHECK (highest_sequence_number >= -1),
    PRIMARY KEY (therapy_session_id, stream_id)
);

INSERT INTO telemetry_stream_progress (therapy_session_id, stream_id, highest_sequence_number)
SELECT therapy_session_id, stream_id, max(sequence_number)
FROM telemetry_batch_inbox
GROUP BY therapy_session_id, stream_id;
