package com.neurovision.rehab.clinical.application.internal.commandservices;

import com.neurovision.rehab.clinical.application.ports.out.persistence.RehabilitationContextStore;
import com.neurovision.rehab.clinical.domain.model.valueobjects.RehabilitationContext;
import java.time.Clock;
import java.util.UUID;
import java.util.function.Supplier;
import com.neurovision.rehab.clinical.domain.model.valueobjects.CognitoSubject;
import static com.neurovision.rehab.clinical.application.internal.commandservices.RehabilitationContextFailure.Kind.*;

public final class RehabilitationContextService {
    private final RehabilitationContextStore store;
    private final Clock clock;
    public RehabilitationContextService(RehabilitationContextStore store, Clock clock) {
        this.store = java.util.Objects.requireNonNull(store);
        this.clock = java.util.Objects.requireNonNull(clock);
    }
    public RehabilitationContextStore.Snapshot read(UUID patient, String doctor) {
        validate(patient, doctor);
        return safe(() -> checked(patient, store.readAssigned(patient, doctor)
                .orElseThrow(() -> new RehabilitationContextFailure(FORBIDDEN))));
    }
    public RehabilitationContextStore.Snapshot patch(UUID patient, String doctor, long expectedVersion,
            RehabilitationContext.Patch patch) {
        validate(patient, doctor);
        if (expectedVersion < 0 || patch == null) throw new RehabilitationContextFailure(INVALID_REQUEST);
        return safe(() -> {
            var current = checked(patient, store.readAssigned(patient, doctor)
                    .orElseThrow(() -> new RehabilitationContextFailure(FORBIDDEN)));
            if (current.version() != expectedVersion) throw new RehabilitationContextFailure(CONFLICT);
            RehabilitationContext desired;
            try { desired = current.context().apply(patch); }
            catch (IllegalArgumentException invalid) { throw new RehabilitationContextFailure(INVALID_REQUEST); }
            return checked(patient, store.replaceAssigned(patient, doctor, expectedVersion, desired, clock.instant())
                    .orElseThrow(() -> new RehabilitationContextFailure(FORBIDDEN)));
        });
    }

    private static void validate(UUID patient, String doctor) {
        try {
            if (!new CognitoSubject(doctor).value().equals(doctor)) throw new IllegalArgumentException();
        } catch (RuntimeException invalid) { throw new RehabilitationContextFailure(FORBIDDEN); }
        if (patient == null) throw new RehabilitationContextFailure(INVALID_REQUEST);
    }

    private static RehabilitationContextStore.Snapshot checked(UUID patient, RehabilitationContextStore.Snapshot value) {
        if (value == null || !patient.equals(value.clinicalPatientId())) throw new RehabilitationContextFailure(UNAVAILABLE);
        return value;
    }

    private static RehabilitationContextStore.Snapshot safe(Supplier<RehabilitationContextStore.Snapshot> action) {
        try { return action.get(); }
        catch (RehabilitationContextFailure known) { throw known; }
        catch (RuntimeException unavailable) { throw new RehabilitationContextFailure(UNAVAILABLE); }
    }
}
