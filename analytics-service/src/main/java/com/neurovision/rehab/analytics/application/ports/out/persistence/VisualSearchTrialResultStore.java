package com.neurovision.rehab.analytics.application.ports.out.persistence;

import com.neurovision.rehab.analytics.domain.model.valueobjects.VisualSearchTrialContext;
import com.neurovision.rehab.analytics.domain.model.valueobjects.VisualSearchTrialResult;
import io.smallrye.mutiny.Uni;
import java.util.Optional;
import java.util.UUID;

/** Future adapter must use authoritative reads and durable acknowledgement; no implementation exists in this slice. */
public interface VisualSearchTrialResultStore {
    Uni<Optional<Stored>> find(UUID therapySessionId, String trialId);

    /** Atomic insert-only on (session, trial); never overwrite. Return the confirmed winner on a race. */
    Uni<WriteResult> insertIfAbsent(Stored candidate);

    record Stored(VisualSearchTrialContext context, VisualSearchTrialResult result) {
        public Stored {
            if (context == null) throw new IllegalArgumentException("Invalid stored Visual Search result");
            context.validate(result);
        }
        @Override public String toString() { return "StoredVisualSearchTrial[redacted]"; }
    }

    /** created=true only after confirmed insertion; false requires an existing durably stored winner. */
    record WriteResult(boolean created, Stored stored) {
        public WriteResult {
            if (stored == null) throw new IllegalArgumentException("Invalid Visual Search write acknowledgement");
        }
        @Override public String toString() { return "VisualSearchTrialWrite[redacted]"; }
    }
}
