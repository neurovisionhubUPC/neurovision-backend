ALTER TABLE outbox_events
    ADD COLUMN next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN locked_by VARCHAR(128),
    ADD COLUMN locked_until TIMESTAMPTZ,
    ADD CONSTRAINT ck_outbox_events_lock
        CHECK (
            (locked_by IS NULL AND locked_until IS NULL)
            OR (locked_by IS NOT NULL AND locked_until IS NOT NULL)
        );

DROP INDEX ix_outbox_events_unpublished;

CREATE INDEX ix_outbox_events_ready
    ON outbox_events (next_attempt_at, occurred_at, id)
    WHERE published_at IS NULL;
