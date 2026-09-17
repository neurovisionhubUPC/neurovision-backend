package com.neurovision.rehab.telemetry.infrastructure.persistence.timescale;

import com.neurovision.rehab.telemetry.application.ports.out.persistence.TelemetryBatchPersistencePort;
import com.neurovision.rehab.telemetry.application.ports.out.persistence.TelemetryPersistenceRejectedException;
import com.neurovision.rehab.telemetry.domain.model.valueobjects.TelemetryBatch;
import com.neurovision.rehab.telemetry.domain.services.TelemetrySequencePolicy;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.pgclient.PgPool;
import io.vertx.mutiny.sqlclient.SqlConnection;
import io.vertx.mutiny.sqlclient.Tuple;
import io.vertx.pgclient.PgException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/** PgPool hot path: one inbox claim and all samples in one database transaction. */
@ApplicationScoped
public class PgTelemetryBatchPersistenceAdapter implements TelemetryBatchPersistencePort {
    private static final int INSERT_CHUNK_SIZE = 256;
    private static final String ENSURE_STREAM = """
            INSERT INTO telemetry_stream_progress (therapy_session_id, stream_id, highest_sequence_number)
            VALUES ($1, $2, -1) ON CONFLICT DO NOTHING
            """;
    private static final String LOCK_STREAM = """
            SELECT highest_sequence_number FROM telemetry_stream_progress
            WHERE therapy_session_id = $1 AND stream_id = $2 FOR UPDATE
            """;
    private static final String CLAIM = """
            INSERT INTO telemetry_batch_inbox
                (batch_id, payload_sha256, therapy_session_id, stream_id, sequence_number,
                 sequence_quality, missing_sequence_start, missing_sequence_end)
            VALUES ($1, $2, $3, $4, $5, $6, $7, $8) ON CONFLICT DO NOTHING
            """;
    private static final String ADVANCE_STREAM = """
            UPDATE telemetry_stream_progress SET highest_sequence_number = GREATEST(highest_sequence_number, $3)
            WHERE therapy_session_id = $1 AND stream_id = $2
            """;
    private static final String SAMPLE = """
            INSERT INTO telemetry_samples
                (sample_time, batch_id, therapy_session_id, stream_id, sequence_number,
                 sample_kind, sample_index, offset_ms, server_received_at, x, y, confidence,
                 player_x, player_y, target_x, target_y, score, speed, progress)
            VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12,
                    $13, $14, $15, $16, $17, $18, $19)
            """;
    private final Instance<PgPool> pool;

    @Inject
    public PgTelemetryBatchPersistenceAdapter(Instance<PgPool> pool) {
        this.pool = pool;
    }

    @Override
    public Uni<Result> persist(TelemetryBatch batch, Instant serverReceivedAt, String payloadSha256) {
        // The stream lock serializes classification; uniqueness remains database-enforced.
        return pool.get().withTransaction(conn -> conn.preparedQuery(ENSURE_STREAM)
                .execute(Tuple.of(batch.therapySessionId(), batch.streamId()))
                .onItem().transformToUni(ignored -> conn.preparedQuery(LOCK_STREAM)
                        .execute(Tuple.of(batch.therapySessionId(), batch.streamId())))
                .onItem().transformToUni(rows -> {
                    long highest = rows.iterator().next().getLong("highest_sequence_number");
                    var quality = TelemetrySequencePolicy.assess(highest, batch.sequence());
                    var claimValues = Tuple.tuple()
                            .addUUID(batch.batchId()).addString(payloadSha256)
                            .addUUID(batch.therapySessionId()).addUUID(batch.streamId())
                            .addLong(batch.sequence()).addString(quality.quality().name())
                            .addValue(quality.missingStart()).addValue(quality.missingEnd());
                    return conn.preparedQuery(CLAIM).execute(claimValues)
                            .onItem().transformToUni(claim -> claim.rowCount() == 0
                                    ? verifyDuplicate(conn, batch, payloadSha256)
                                    : conn.preparedQuery(ADVANCE_STREAM)
                                            .execute(Tuple.of(batch.therapySessionId(), batch.streamId(),
                                                    batch.sequence()))
                                            .chain(() -> insertSamples(conn, batch, serverReceivedAt))
                                            .replaceWith(Result.INSERTED));
                })).onFailure().transform(this::classifyDatabaseFailure);
    }

    private Uni<Result> verifyDuplicate(SqlConnection conn, TelemetryBatch batch, String payloadSha256) {
        return conn.preparedQuery("SELECT payload_sha256 FROM telemetry_batch_inbox WHERE batch_id = $1")
                .execute(Tuple.of(batch.batchId()))
                .onItem().transform(rows -> {
                    var iterator = rows.iterator();
                    if (!iterator.hasNext() || !payloadSha256.equals(iterator.next().getString("payload_sha256"))) {
                        throw new TelemetryPersistenceRejectedException();
                    }
                    return Result.DUPLICATE;
                });
    }

    private Throwable classifyDatabaseFailure(Throwable failure) {
        if (failure instanceof TelemetryPersistenceRejectedException) {
            return failure;
        }
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof PgException pg) {
                String state = pg.getSqlState();
                if (state != null && (state.startsWith("22") || state.startsWith("23"))) {
                    return new TelemetryPersistenceRejectedException();
                }
                break;
            }
        }
        return failure;
    }

    private Uni<Void> insertSamples(SqlConnection conn, TelemetryBatch batch, Instant serverReceivedAt) {
        List<Tuple> samples = new ArrayList<>(batch.gazeSamples().size() + batch.gameplaySamples().size());
        int index = 0;
        for (var gaze : batch.gazeSamples()) {
            samples.add(common(batch, serverReceivedAt, "GAZE", index++, gaze.offsetMs())
                    .addDouble(gaze.x()).addDouble(gaze.y()).addDouble(gaze.confidence())
                    .addValue(null).addValue(null).addValue(null).addValue(null)
                    .addValue(null).addValue(null).addValue(null));
        }
        index = 0;
        for (var game : batch.gameplaySamples()) {
            samples.add(common(batch, serverReceivedAt, "GAMEPLAY", index++, game.offsetMs())
                    .addValue(null).addValue(null).addValue(null)
                    .addDouble(game.playerX()).addDouble(game.playerY())
                    .addDouble(game.targetX()).addDouble(game.targetY())
                    .addLong(game.score()).addDouble(game.speed()).addDouble(game.progress()));
        }
        Uni<Void> insertion = Uni.createFrom().voidItem();
        for (int start = 0; start < samples.size(); start += INSERT_CHUNK_SIZE) {
            List<Tuple> chunk = List.copyOf(samples.subList(start, Math.min(samples.size(), start + INSERT_CHUNK_SIZE)));
            insertion = insertion.chain(() -> conn.preparedQuery(SAMPLE).executeBatch(chunk).replaceWithVoid());
        }
        return insertion;
    }

    private Tuple common(TelemetryBatch batch, Instant receivedAt, String kind, int index, long offsetMs) {
        return Tuple.tuple()
                .addOffsetDateTime(utc(batch.capturedAt().plusMillis(offsetMs)))
                .addUUID(batch.batchId()).addUUID(batch.therapySessionId()).addUUID(batch.streamId())
                .addLong(batch.sequence()).addString(kind).addInteger(index).addLong(offsetMs)
                .addOffsetDateTime(utc(receivedAt));
    }

    private OffsetDateTime utc(Instant time) {
        return time.atOffset(ZoneOffset.UTC);
    }
}
