package com.neurovision.rehab.analytics.application.ports.out.persistence;

import com.neurovision.rehab.analytics.domain.model.valueobjects.StreamSummaryProjection;
import io.smallrye.mutiny.Uni;

public interface StreamSummaryProjectionPort {
    /**
     * Atomically inserts or advances one projection identity only when admittedBatchCount is greater than the
     * stored revision. Equal or older revisions return NOT_ADVANCED without replacing the stored summary.
     */
    Uni<WriteResult> storeIfNewer(StreamSummaryProjection projection);

    enum WriteResult {
        INSERTED,
        ADVANCED,
        NOT_ADVANCED
    }
}
