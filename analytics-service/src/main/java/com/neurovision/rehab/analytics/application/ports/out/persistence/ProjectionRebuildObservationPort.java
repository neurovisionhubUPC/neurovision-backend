package com.neurovision.rehab.analytics.application.ports.out.persistence;

import com.neurovision.rehab.analytics.domain.model.valueobjects.*;
import io.smallrye.mutiny.Uni;
import java.time.Duration;
import java.util.List;

/**
 * Nonblocking, read-only observation boundary; no index creation or repair is permitted.
 * Adapters must query the exact identity without filtering malformed matching rows, apply the
 * document sentinel and server timeout before materializing results, and attest encoded projected
 * BSON sizes. Source order is (sequence, batchId). Empty lists mean absence, never incomplete reads.
 * Mapping failures must fail the Uni, not drop rows. Missing legacy inbox schema is represented by
 * null; its batch and persisted admission quality must already be reconstructed losslessly.
 * Cancellation is best effort: a client timeout does not prove cancellation of server work.
 * Typed records are private application data, never diagnostic output.
 */
public interface ProjectionRebuildObservationPort {
    Uni<List<SourceRow>> readSource(TelemetryStreamIdentity identity, ReadBounds bounds);
    Uni<List<ProjectionRow>> readProjection(AnalyticsProjectionIdentity identity, ReadBounds bounds);

    record ReadBounds(int maxDocuments, long maxDocumentBytes, long maxTotalBytes, Duration queryTimeout) {
        public ReadBounds {
            if (maxDocuments < 1 || maxDocumentBytes < 1 || maxTotalBytes < maxDocumentBytes
                    || queryTimeout == null || queryTimeout.isNegative() || queryTimeout.isZero()
                    || queryTimeout.compareTo(Duration.ofSeconds(2)) > 0) {
                throw new IllegalArgumentException("Invalid observation bounds");
            }
        }
    }

    record SourceRow(AdmittedTelemetryBatch admitted, Integer inboxSchemaVersion, String status,
                     long encodedProjectedBsonBytes) {
        @Override public String toString() { return "SourceRow[redacted]"; }
    }
    record ProjectionRow(AnalyticsSummaryProjection projection, long encodedProjectedBsonBytes) {
        @Override public String toString() { return "ProjectionRow[redacted]"; }
    }

    enum ReadFailure { SOURCE_INVALID, PROJECTION_INVALID, UNAVAILABLE }

    /** Early adapter rejection before typed rows can safely be reconstructed. */
    enum ObservationLimit { SOURCE_BATCHES, BATCH_SAMPLES, TOTAL_SAMPLES, SOURCE_DOCUMENT_BYTES,
        SOURCE_PASS_BYTES, PROJECTION_DOCUMENT_BYTES }

    final class ObservationLimitException extends RuntimeException {
        private final ObservationLimit limit;
        public ObservationLimitException(ObservationLimit limit) {
            super("Observation limit exceeded", null, false, false);
            this.limit = java.util.Objects.requireNonNull(limit);
        }
        public ObservationLimit limit() { return limit; }
    }

    /** A server or client observation deadline expired, with no cancellation guarantee. */
    final class ObservationDeadlineException extends RuntimeException {
        public ObservationDeadlineException() { super("Observation deadline exceeded", null, false, false); }
    }

    /** Fixed adapter failure; never attach database messages, documents or causes. */
    final class ObservationException extends RuntimeException {
        private final ReadFailure failure;
        public ObservationException(ReadFailure failure) {
            super("Observation unavailable", null, false, false);
            this.failure = failure;
        }
        public ReadFailure failure() { return failure; }
    }
}
