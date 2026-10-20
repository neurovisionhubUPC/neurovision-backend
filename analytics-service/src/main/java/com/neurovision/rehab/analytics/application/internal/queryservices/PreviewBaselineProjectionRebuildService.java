package com.neurovision.rehab.analytics.application.internal.queryservices;

import com.neurovision.rehab.analytics.application.ports.out.persistence.ProjectionRebuildObservationPort;
import com.neurovision.rehab.analytics.application.ports.out.persistence.ProjectionRebuildObservationPort.*;
import com.neurovision.rehab.analytics.domain.model.valueobjects.*;
import com.neurovision.rehab.analytics.domain.services.BaselineTelemetryMetricsCalculator;
import com.neurovision.rehab.analytics.domain.model.valueobjects.StreamSummaryProjection;
import io.smallrye.mutiny.Uni;
import java.time.Duration;
import java.util.*;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * Internal baseline-v1 preview only, deliberately not a CDI bean or public entry point.
 * Observes P0/S0, calculates privately, then observes S1/P1 without retries. Repeated reads are
 * optimistic evidence, NOT a snapshot or linearizable proof: ABA and changes after P1 remain possible.
 * There is no write authority or repair plan in the result. Encoded byte counts are adapter
 * attestations; validation after decoding gives logical bounds, not hard heap/network bounds.
 * Nonblocking ports are required. Synchronous work cannot be preempted; expired results are rejected.
 */
public final class PreviewBaselineProjectionRebuildService {
    private static final String VERSION = "baseline-v1";
    private static final long GLOBAL_NANOS = Duration.ofSeconds(5).toNanos();
    private static final long QUERY_NANOS = Duration.ofSeconds(2).toNanos();
    private static final long SOURCE_BYTES = 128 * 1024;
    private static final long PASS_BYTES = 4 * 1024 * 1024;
    private static final long PROJECTION_BYTES = 256 * 1024;
    private final ProjectionRebuildObservationPort observations;
    private final Function<List<AdmittedTelemetryBatch>, BaselineStreamSummary> calculate;
    private final LongSupplier nanoTime;

    public PreviewBaselineProjectionRebuildService(ProjectionRebuildObservationPort observations) {
        this(observations, new BaselineTelemetryMetricsCalculator()::calculate, System::nanoTime);
    }

    // Explicit dependencies keep deterministic offline clocks/calculation probes outside production wiring.
    public PreviewBaselineProjectionRebuildService(ProjectionRebuildObservationPort observations,
            Function<List<AdmittedTelemetryBatch>, BaselineStreamSummary> calculate, LongSupplier nanoTime) {
        this.observations = Objects.requireNonNull(observations);
        this.calculate = Objects.requireNonNull(calculate);
        this.nanoTime = Objects.requireNonNull(nanoTime);
    }

    public Uni<Outcome> preview(UUID therapySessionId, UUID streamId, String calculationVersion) {
        return prepare(therapySessionId, streamId, calculationVersion).map(PreparedProjection::outcome);
    }

    /** Internal continuation only; never expose a prepared candidate through REST, logs or a stored plan. */
    public <T> Uni<T> withPreparedProjection(UUID therapySessionId, UUID streamId, String calculationVersion,
            Function<PreparedProjection, Uni<T>> continuation) {
        Objects.requireNonNull(continuation);
        // Continuation errors deliberately stay outside observation failure reduction.
        return prepare(therapySessionId, streamId, calculationVersion).flatMap(prepared -> continuation.apply(prepared));
    }

    private Uni<PreparedProjection> prepare(UUID therapySessionId, UUID streamId, String calculationVersion) {
        if (therapySessionId == null || streamId == null || calculationVersion == null
                || calculationVersion.isBlank()) return Uni.createFrom().item(preparedFailure(Status.INVALID_REQUEST));
        if (!VERSION.equals(calculationVersion)) return Uni.createFrom().item(preparedFailure(Status.UNSUPPORTED_VERSION));
        var identity = new AnalyticsProjectionIdentity(therapySessionId, streamId, VERSION);
        var streamIdentity = new TelemetryStreamIdentity(therapySessionId, streamId);
        return Uni.createFrom().deferred(() -> {
            var budget = new Budget(nanoTime.getAsLong());
            return projection(identity, budget).flatMap(p0 -> source(streamIdentity, budget).flatMap(s0 -> {
                final BaselineStreamSummary candidate;
                try {
                    candidate = s0.isEmpty() ? null : calculate.apply(s0.stream().map(SourceRow::admitted).toList());
                    if (!s0.isEmpty() && (candidate == null || !identity.therapySessionId().equals(candidate.therapySessionId())
                            || !identity.streamId().equals(candidate.streamId()) || !VERSION.equals(candidate.calculationVersion()))) {
                        throw new Rejected(Status.SOURCE_INVALID, null);
                    }
                } catch (IllegalArgumentException | NullPointerException invalid) {
                    throw new Rejected(Status.SOURCE_INVALID, null);
                }
                budget.remaining();
                return source(streamIdentity, budget).flatMap(s1 -> projection(identity, budget).map(p1 -> {
                    budget.remaining();
                    if (!sameSource(s0, s1)) return preparedFailure(Status.SOURCE_CHANGED);
                    if (!Objects.equals(p0, p1)) return preparedFailure(Status.PROJECTION_CHANGED);
                    return new PreparedProjection(classify(s0.size(), p1, candidate),
                            candidate == null ? null : new StreamSummaryProjection(
                                    identity, s0.size(), candidate), p1, budget);
                }));
            }));
        }).onFailure().recoverWithItem(error -> new PreparedProjection(safeFailure(error), null, null, null));
    }

    private Uni<List<SourceRow>> source(TelemetryStreamIdentity identity, Budget budget) {
        return read(budget, timeout -> observations.readSource(identity,
                new ReadBounds(65, SOURCE_BYTES, PASS_BYTES, timeout))).map(rows -> {
            if (rows.size() > 64) throw new Rejected(Status.LIMIT_EXCEEDED, Limit.SOURCE_BATCHES);
            long bytes = 0;
            long samples = 0;
            try {
                for (var row : rows) {
                    if (row == null || row.admitted() == null || !"ADMITTED".equals(row.status())
                            || (row.inboxSchemaVersion() != null && row.inboxSchemaVersion() != 2)
                            || row.encodedProjectedBsonBytes() <= 0) throw new Rejected(Status.SOURCE_INVALID, null);
                    if (row.encodedProjectedBsonBytes() > SOURCE_BYTES)
                        throw new Rejected(Status.LIMIT_EXCEEDED, Limit.SOURCE_DOCUMENT_BYTES);
                    bytes += row.encodedProjectedBsonBytes();
                    if (bytes > PASS_BYTES) throw new Rejected(Status.LIMIT_EXCEEDED, Limit.SOURCE_PASS_BYTES);
                    var batch = row.admitted().batch();
                    long count = (long) batch.gazeSamples().size() + batch.gameplaySamples().size();
                    if (count > 1000) throw new Rejected(Status.LIMIT_EXCEEDED, Limit.BATCH_SAMPLES);
                    samples += count;
                    if (samples > 64000) throw new Rejected(Status.LIMIT_EXCEEDED, Limit.TOTAL_SAMPLES);
                }
                if (!rows.isEmpty()) new AdmittedTelemetryStream(identity, rows.size(), rows.stream().map(SourceRow::admitted).toList());
                var ordered = rows.stream().sorted(Comparator.comparingLong((SourceRow r) -> r.admitted().batch().sequence())
                        .thenComparing(r -> r.admitted().batch().batchId())).toList();
                budget.remaining();
                return ordered;
            } catch (IllegalArgumentException | NullPointerException invalid) {
                throw new Rejected(Status.SOURCE_INVALID, null);
            }
        });
    }

    private Uni<AnalyticsSummaryProjection> projection(AnalyticsProjectionIdentity identity, Budget budget) {
        return read(budget, timeout -> observations.readProjection(identity,
                new ReadBounds(2, PROJECTION_BYTES, 2 * PROJECTION_BYTES, timeout))).map(rows -> {
            if (rows.size() > 1) throw new Rejected(Status.PROJECTION_INVALID, null);
            if (rows.isEmpty()) return null;
            var row = rows.getFirst();
            if (row == null || row.projection() == null || !identity.equals(row.projection().identity())
                    || row.encodedProjectedBsonBytes() <= 0) throw new Rejected(Status.PROJECTION_INVALID, null);
            if (row.encodedProjectedBsonBytes() > PROJECTION_BYTES)
                throw new Rejected(Status.LIMIT_EXCEEDED, Limit.PROJECTION_DOCUMENT_BYTES);
            budget.remaining();
            return row.projection();
        });
    }

    private <T> Uni<List<T>> read(Budget budget, Function<Duration, Uni<List<T>>> request) {
        return Uni.createFrom().deferred(() -> {
            long remaining = budget.remaining();
            var timeout = Duration.ofNanos(Math.min(QUERY_NANOS, remaining));
            var result = request.apply(timeout);
            if (result == null) throw new Rejected(Status.UNAVAILABLE, null);
            // Clip again after invocation, accounting for any synchronous adapter overhead.
            var wait = Duration.ofNanos(Math.min(timeout.toNanos(), budget.remaining()));
            return result.ifNoItem().after(wait).failWith(() -> new Rejected(Status.DEADLINE_EXCEEDED, null))
                    .map(rows -> {
                        budget.remaining();
                        if (rows == null) throw new Rejected(Status.UNAVAILABLE, null);
                        // Copy immediately; null elements are handled as invalid observations below.
                        return Collections.unmodifiableList(new ArrayList<>(rows));
                    });
        });
    }

    private static boolean sameSource(List<SourceRow> a, List<SourceRow> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            var left = a.get(i);
            var right = b.get(i);
            // Compare complete reconstructed content, schema and state, not hashes/counts alone.
            if (!left.admitted().equals(right.admitted()) || !Objects.equals(left.inboxSchemaVersion(), right.inboxSchemaVersion())
                    || !left.status().equals(right.status())) return false;
        }
        return true;
    }

    private static Outcome classify(int count, AnalyticsSummaryProjection projection, BaselineStreamSummary candidate) {
        Long revision = projection == null ? null : projection.revision();
        Status status;
        if (count == 0) status = projection == null ? Status.NO_SOURCE : Status.SOURCE_PROJECTION_INCONSISTENT;
        else if (projection == null) status = Status.PROJECTION_MISSING;
        else if (revision > count) status = Status.SOURCE_PROJECTION_INCONSISTENT;
        else if (revision < count) status = Status.PROJECTION_STALE;
        else status = projection.summary().equals(candidate) ? Status.CONSISTENT : Status.SAME_REVISION_MISMATCH;
        return new Outcome(status, null, (long) count, revision);
    }

    private Outcome safeFailure(Throwable thrown) {
        if (thrown instanceof Rejected rejected) return new Outcome(rejected.status, rejected.limit, null, null);
        if (thrown instanceof ObservationDeadlineException) return failure(Status.DEADLINE_EXCEEDED);
        if (thrown instanceof ObservationLimitException exceeded) {
            var limit = switch (exceeded.limit()) {
                case SOURCE_BATCHES -> Limit.SOURCE_BATCHES;
                case BATCH_SAMPLES -> Limit.BATCH_SAMPLES;
                case TOTAL_SAMPLES -> Limit.TOTAL_SAMPLES;
                case SOURCE_DOCUMENT_BYTES -> Limit.SOURCE_DOCUMENT_BYTES;
                case SOURCE_PASS_BYTES -> Limit.SOURCE_PASS_BYTES;
                case PROJECTION_DOCUMENT_BYTES -> Limit.PROJECTION_DOCUMENT_BYTES;
            };
            return new Outcome(Status.LIMIT_EXCEEDED, limit, null, null);
        }
        if (thrown instanceof ObservationException observation && observation.failure() != null) {
            return failure(switch (observation.failure()) {
                case SOURCE_INVALID -> Status.SOURCE_INVALID;
                case PROJECTION_INVALID -> Status.PROJECTION_INVALID;
                case UNAVAILABLE -> Status.UNAVAILABLE;
            });
        }
        return failure(Status.UNAVAILABLE);
    }

    private static Outcome failure(Status status) { return new Outcome(status, null, null, null); }

    private PreparedProjection preparedFailure(Status status) {
        return new PreparedProjection(failure(status), null, null, null);
    }

    /** Ephemeral in-process evidence; not serializable authority and never diagnostic data. */
    public static final class PreparedProjection {
        private final Outcome outcome;
        private final StreamSummaryProjection candidate;
        private final AnalyticsSummaryProjection expected;
        private final Budget budget;
        private PreparedProjection(Outcome outcome,
                StreamSummaryProjection candidate,
                AnalyticsSummaryProjection expected, Budget budget) {
            this.outcome = outcome;
            this.candidate = candidate;
            this.expected = expected;
            this.budget = budget;
        }
        public Outcome outcome() { return outcome; }
        public StreamSummaryProjection candidate() { return candidate; }
        public AnalyticsSummaryProjection expected() { return expected; }
        public long remainingBudgetNanos() {
            if (budget == null) throw new IllegalStateException("No preparation budget");
            return budget.remaining();
        }
        public Duration remainingWriteTimeout() {
            return Duration.ofNanos(Math.min(QUERY_NANOS, remainingBudgetNanos()));
        }
        @Override public String toString() { return "PreparedProjection[redacted]"; }
    }

    private final class Budget {
        private final long start;
        Budget(long start) { this.start = start; }
        long remaining() {
            long elapsed = nanoTime.getAsLong() - start;
            if (elapsed < 0 || elapsed >= GLOBAL_NANOS) throw new Rejected(Status.DEADLINE_EXCEEDED, null);
            return GLOBAL_NANOS - elapsed;
        }
    }

    private static final class Rejected extends RuntimeException {
        final Status status;
        final Limit limit;
        Rejected(Status status, Limit limit) {
            super(status.code(), null, false, false);
            this.status = status;
            this.limit = limit;
        }
    }

    public enum Status {
        CONSISTENT, PROJECTION_MISSING, PROJECTION_STALE, SAME_REVISION_MISMATCH,
        SOURCE_PROJECTION_INCONSISTENT, NO_SOURCE, INVALID_REQUEST, UNSUPPORTED_VERSION,
        SOURCE_INVALID, PROJECTION_INVALID, SOURCE_CHANGED, PROJECTION_CHANGED,
        LIMIT_EXCEEDED, UNAVAILABLE, DEADLINE_EXCEEDED;
        public String code() { return name().toLowerCase(Locale.ROOT); }
    }
    public enum Limit { SOURCE_BATCHES, BATCH_SAMPLES, TOTAL_SAMPLES, SOURCE_DOCUMENT_BYTES,
        SOURCE_PASS_BYTES, PROJECTION_DOCUMENT_BYTES }

    /** Safe metadata only; no identifiers, source contents, hashes, summaries or exception messages. */
    public record Outcome(Status status, Limit limit, Long sourceBatchCount, Long projectionRevision) {
        public String calculationVersion() { return VERSION; }
        public String consistencyMode() { return "optimistic_repeated_read"; }
    }
}
