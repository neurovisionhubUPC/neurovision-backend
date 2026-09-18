-- Bounded keyset reads use the exact predicate and ordering prefix below.
-- This non-unique index retains the existing V1 uniqueness and write semantics.
CREATE INDEX telemetry_samples_replay_page_idx
    ON telemetry_samples (therapy_session_id, stream_id, sample_time,
                          batch_id, sample_kind, sample_index);
