package com.neurovision.rehab.analytics.application.ports.out.persistence;

import com.neurovision.rehab.analytics.domain.model.valueobjects.SystematicExplorationTrialContext;
import com.neurovision.rehab.analytics.domain.model.valueobjects.SystematicExplorationTrialResult;
import io.smallrye.mutiny.Uni;
import java.util.Optional;
import java.util.UUID;

/** Future adapter must use authoritative reads and durable acknowledgement; no implementation exists in this slice. */
public interface SystematicExplorationTrialResultStore {
    Uni<Optional<Stored>> find(UUID therapySessionId, String trialId);

    /** Atomic insert-only on (session, trial); never overwrite. Return the confirmed winner on a race. */
    Uni<WriteResult> insertIfAbsent(Stored candidate);

    record Stored(SystematicExplorationTrialContext context, SystematicExplorationTrialResult result) {
        public Stored {
            if (context == null) throw new IllegalArgumentException("Invalid stored Systematic Exploration result");
            context.validate(result);
        }
        @Override public String toString() { return "StoredSystematicExplorationTrial[redacted]"; }
    }

    /** created=true only after confirmed insertion; false requires an existing durably stored winner. */
    record WriteResult(boolean created, Stored stored) {
        public WriteResult {
            if (stored == null) throw new IllegalArgumentException("Invalid Systematic Exploration write acknowledgement");
        }
        @Override public String toString() { return "SystematicExplorationTrialWrite[redacted]"; }
    }
}
