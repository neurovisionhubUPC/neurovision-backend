package com.neurovision.rehab.telemetry.infrastructure.persistence.timescale;

import com.neurovision.rehab.telemetry.application.ports.out.persistence.HistoricalTelemetryReadPort;
import io.vertx.mutiny.pgclient.PgPool;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class PgHistoricalTelemetryReadAdapter implements HistoricalTelemetryReadPort {
    private static final Duration QUERY_TIMEOUT = Duration.ofSeconds(5);
    private static final String FIRST_PAGE = """
            SELECT sample_time, batch_id, sequence_number, sample_kind, sample_index,
                   offset_ms, server_received_at, x, y, confidence, player_x, player_y,
                   target_x, target_y, score, speed, progress
            FROM telemetry_samples
            WHERE therapy_session_id = $1 AND stream_id = $2
              AND sample_time >= $3 AND sample_time < $4
            ORDER BY sample_time, batch_id, sample_kind, sample_index
            LIMIT $5
            """;
    private static final String NEXT_PAGE = """
            SELECT sample_time, batch_id, sequence_number, sample_kind, sample_index,
                   offset_ms, server_received_at, x, y, confidence, player_x, player_y,
                   target_x, target_y, score, speed, progress
            FROM telemetry_samples
            WHERE therapy_session_id = $1 AND stream_id = $2
              AND sample_time >= $3 AND sample_time < $4
              AND (sample_time, batch_id, sample_kind, sample_index) > ($5, $6, $7, $8)
            ORDER BY sample_time, batch_id, sample_kind, sample_index
            LIMIT $9
            """;
    private final Instance<PgPool> pool;

    @Inject
    public PgHistoricalTelemetryReadAdapter(Instance<PgPool> pool) {
        this.pool = pool;
    }

    @Override
    public List<Sample> read(UUID sessionId, UUID streamId, Instant from, Instant to,
            Cursor after, int fetchLimit) {
        Tuple values = Tuple.tuple().addUUID(sessionId).addUUID(streamId)
                .addOffsetDateTime(utc(from)).addOffsetDateTime(utc(to));
        if (after != null) {
            values.addOffsetDateTime(utc(after.sampleTime())).addUUID(after.batchId())
                    .addString(after.kind()).addInteger(after.index());
        }
        values.addInteger(fetchLimit);
        var rows = pool.get().preparedQuery(after == null ? FIRST_PAGE : NEXT_PAGE)
                .execute(values).await().atMost(QUERY_TIMEOUT);
        List<Sample> result = new ArrayList<>(rows.size());
        for (var row : rows) {
            result.add(new Sample(row.getOffsetDateTime("sample_time").toInstant(),
                    row.getUUID("batch_id"), row.getLong("sequence_number"),
                    row.getString("sample_kind"), row.getInteger("sample_index"),
                    row.getLong("offset_ms"), row.getOffsetDateTime("server_received_at").toInstant(),
                    row.getDouble("x"), row.getDouble("y"), row.getDouble("confidence"),
                    row.getDouble("player_x"), row.getDouble("player_y"),
                    row.getDouble("target_x"), row.getDouble("target_y"), row.getLong("score"),
                    row.getDouble("speed"), row.getDouble("progress")));
        }
        return result;
    }

    private static OffsetDateTime utc(Instant time) { return time.atOffset(ZoneOffset.UTC); }
}
