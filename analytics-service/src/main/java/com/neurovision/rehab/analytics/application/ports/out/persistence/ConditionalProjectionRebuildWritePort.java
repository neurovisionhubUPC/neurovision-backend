package com.neurovision.rehab.analytics.application.ports.out.persistence;

import com.neurovision.rehab.analytics.domain.model.valueobjects.AnalyticsSummaryProjection;
import com.neurovision.rehab.analytics.domain.model.valueobjects.StreamSummaryProjection;
import io.smallrye.mutiny.Uni;
import java.time.Duration;
import java.util.Objects;

/**
 * One nonblocking, acknowledged conditional mutation; no automatic resend or index creation.
 * An absent expected projection requires insert-only with the existing unique identity index.
 * Otherwise compare identity, revision AND full relevant expected content atomically, then
 * update without upsert. A lost result can coexist with a committed write. Cancellation
 * does not prove rollback. Adapters must honor the supplied positive, at-most-two-second
 * timeout and must not modify the inbox, other versions, or the online projector contract.
 * This boundary is deliberately unwired until a separately verified adapter exists.
 */
public interface ConditionalProjectionRebuildWritePort {
    Uni<WriteResult> writeIfUnchanged(WriteRequest request, Duration timeout);

    enum WriteResult { INSERTED, REPLACED, PRECONDITION_FAILED }

    /** Ephemeral application data, not an authorization token or printable repair plan. */
    record WriteRequest(StreamSummaryProjection candidate, AnalyticsSummaryProjection expected) {
        public WriteRequest {
            Objects.requireNonNull(candidate, "candidate");
            if (!"baseline-v1".equals(candidate.identity().calculationVersion())
                    || (expected != null && (!candidate.identity().equals(expected.identity())
                    || candidate.admittedBatchCount() <= expected.revision()))) {
                throw new IllegalArgumentException("Invalid conditional rebuild request");
            }
        }
        @Override public String toString() { return "WriteRequest[redacted]"; }
    }
}
