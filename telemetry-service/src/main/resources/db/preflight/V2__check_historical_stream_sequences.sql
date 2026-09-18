-- Run with psql -X -v ON_ERROR_STOP=1 against a stopped, backed-up V1 database.
-- This probe has no write statements and never reports session, stream, or batch IDs.
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;

DO $preflight$
DECLARE
    duplicate_groups BIGINT;
    extra_rows BIGINT;
BEGIN
    SELECT count(*), COALESCE(sum(group_size - 1), 0)
    INTO duplicate_groups, extra_rows
    FROM (
        SELECT count(*) AS group_size
        FROM telemetry_batch_inbox
        GROUP BY therapy_session_id, stream_id, sequence_number
        HAVING count(*) > 1
    ) AS duplicate_sequences;

    RAISE NOTICE 'V2 sequence preflight: duplicate_groups=%, extra_rows=%',
        duplicate_groups, extra_rows;
    IF duplicate_groups > 0 THEN
        RAISE EXCEPTION 'V2 sequence preflight blocked: historical conflicts require manual review';
    END IF;
END
$preflight$;

ROLLBACK;
