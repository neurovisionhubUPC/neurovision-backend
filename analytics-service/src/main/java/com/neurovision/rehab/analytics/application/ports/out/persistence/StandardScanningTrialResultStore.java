package com.neurovision.rehab.analytics.application.ports.out.persistence;

import com.neurovision.rehab.analytics.domain.model.valueobjects.StandardScanningTrialContext;
import com.neurovision.rehab.analytics.domain.model.valueobjects.StandardScanningTrialResult;
import io.smallrye.mutiny.Uni;
import java.util.Optional;
import java.util.UUID;

/** Future adapter must use authoritative reads and durable acknowledgement; no implementation exists in this slice. */
public interface StandardScanningTrialResultStore {
    Uni<Optional<Stored>> find(UUID therapySessionId, String trialId);

    /** Atomic insert-only on (session, trial); never overwrite. Return the confirmed winner on a race. */
    Uni<WriteResult> insertIfAbsent(Stored candidate);

    record Stored(StandardScanningTrialContext context, StandardScanningTrialResult result) {
        public Stored {
            if (context == null) throw new IllegalArgumentException("Invalid stored Standard Scanning result");
            context.validate(result);
        }
        @Override public String toString() { return "StoredStandardScanningTrial[redacted]"; }
    }

    /** created=true only after confirmed insertion; false requires an existing durably stored winner. */
    record WriteResult(boolean created, Stored stored) {
        public WriteResult {
            if (stored == null) throw new IllegalArgumentException("Invalid Standard Scanning write acknowledgement");
        }
        @Override public String toString() { return "StandardScanningTrialWrite[redacted]"; }
    }
}
