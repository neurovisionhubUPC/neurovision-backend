package com.neurovision.rehab.analytics.application.ports.out.persistence;

import com.neurovision.rehab.analytics.application.ports.out.persistence.CompensatorySaccadeTrialResultStore.Stored;
import com.neurovision.rehab.analytics.domain.model.valueobjects.VisualSearchTrialResult.DataOrigin;
import io.smallrye.mutiny.Uni;
import java.util.List;
import java.util.UUID;

/** Read-only capability, deliberately separate from insert-only result storage. No adapter in this slice. */
public interface CompensatorySaccadeTrialResultReader {
    /**
     * Filter by exact session and origin at the data source and fetch at most maxRows, including a sentinel.
     * A shorter list must exhaust the matching observation, never be an arbitrary page or a silent truncation.
     * Enforce the limit before materialization; do not scan/load an unbounded collection then truncate it.
     * Fail on corrupt evidence or unavailable storage rather than returning an empty list. Supply immutable
     * rows/list; enforce a bounded adapter deadline. This is not a session-completeness or snapshot guarantee.
     */
    Uni<List<Stored>> read(UUID therapySessionId, DataOrigin dataOrigin, int maxRows);
}
