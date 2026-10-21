package com.neurovision.rehab.clinical.application.ports.out.persistence;

import com.neurovision.rehab.clinical.domain.model.valueobjects.RehabilitationContext;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Both operations are assignment-scoped; replacement must reauthorize and compare version atomically. */
public interface RehabilitationContextStore {
    Optional<Snapshot> readAssigned(UUID patient, String doctor);
    Optional<Snapshot> replaceAssigned(UUID patient, String doctor, long expectedVersion,
            RehabilitationContext context, Instant changedAt);

    record Snapshot(UUID clinicalPatientId, RehabilitationContext context, long version, Instant updatedAt) {
        public Snapshot {
            Objects.requireNonNull(clinicalPatientId);
            Objects.requireNonNull(context);
            Objects.requireNonNull(updatedAt);
            if (version < 0) throw new IllegalArgumentException("Invalid context version");
        }
        @Override public String toString() { return "ContextSnapshot[redacted]"; }
    }
}
