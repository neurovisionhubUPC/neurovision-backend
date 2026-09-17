CREATE EXTENSION IF NOT EXISTS timescaledb;

-- Global batch uniqueness belongs to an ordinary table: hypertable unique indexes
-- must include their time partition column.
CREATE TABLE telemetry_batch_inbox (
    batch_id UUID PRIMARY KEY,
    payload_sha256 CHAR(64) NOT NULL,
    therapy_session_id UUID NOT NULL,
    stream_id UUID NOT NULL,
    sequence_number BIGINT NOT NULL CHECK (sequence_number >= 0),
    persisted_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE telemetry_samples (
    sample_time TIMESTAMPTZ NOT NULL,
    batch_id UUID NOT NULL,
    therapy_session_id UUID NOT NULL,
    stream_id UUID NOT NULL,
    sequence_number BIGINT NOT NULL CHECK (sequence_number >= 0),
    sample_kind VARCHAR(8) NOT NULL CHECK (sample_kind IN ('GAZE', 'GAMEPLAY')),
    sample_index INTEGER NOT NULL CHECK (sample_index >= 0),
    offset_ms BIGINT NOT NULL CHECK (offset_ms >= 0),
    server_received_at TIMESTAMPTZ NOT NULL,
    x DOUBLE PRECISION,
    y DOUBLE PRECISION,
    confidence DOUBLE PRECISION,
    player_x DOUBLE PRECISION,
    player_y DOUBLE PRECISION,
    target_x DOUBLE PRECISION,
    target_y DOUBLE PRECISION,
    score BIGINT,
    speed DOUBLE PRECISION,
    progress DOUBLE PRECISION,
    CONSTRAINT telemetry_samples_shape CHECK (
        (sample_kind = 'GAZE' AND x IS NOT NULL AND y IS NOT NULL AND confidence IS NOT NULL
            AND player_x IS NULL AND player_y IS NULL AND target_x IS NULL AND target_y IS NULL
            AND score IS NULL AND speed IS NULL AND progress IS NULL)
        OR
        (sample_kind = 'GAMEPLAY' AND x IS NULL AND y IS NULL AND confidence IS NULL
            AND player_x IS NOT NULL AND player_y IS NOT NULL AND target_x IS NOT NULL
            AND target_y IS NOT NULL AND score IS NOT NULL AND speed IS NOT NULL AND progress IS NOT NULL)
    ),
    CONSTRAINT telemetry_samples_normalized CHECK (
        (x IS NULL OR x BETWEEN 0 AND 1) AND (y IS NULL OR y BETWEEN 0 AND 1)
        AND (confidence IS NULL OR confidence BETWEEN 0 AND 1)
        AND (player_x IS NULL OR player_x BETWEEN 0 AND 1)
        AND (player_y IS NULL OR player_y BETWEEN 0 AND 1)
        AND (target_x IS NULL OR target_x BETWEEN 0 AND 1)
        AND (target_y IS NULL OR target_y BETWEEN 0 AND 1)
        AND (progress IS NULL OR progress BETWEEN 0 AND 1)
        AND (score IS NULL OR score >= 0) AND (speed IS NULL OR speed >= 0)
    )
);

SELECT create_hypertable('telemetry_samples', 'sample_time', if_not_exists => TRUE);

CREATE UNIQUE INDEX telemetry_samples_batch_position_uq
    ON telemetry_samples (sample_time, batch_id, sample_kind, sample_index);
CREATE INDEX telemetry_samples_session_stream_time_idx
    ON telemetry_samples (therapy_session_id, stream_id, sample_time DESC);
CREATE INDEX telemetry_samples_batch_idx ON telemetry_samples (batch_id, sample_time DESC);
