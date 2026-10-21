package com.neurovision.rehab.clinical.application.internal.commandservices;

import static org.junit.jupiter.api.Assertions.*;
import static com.neurovision.rehab.clinical.domain.model.valueobjects.RehabilitationContext.Change.*;

import com.neurovision.rehab.clinical.application.ports.out.persistence.RehabilitationContextStore;
import com.neurovision.rehab.clinical.domain.model.valueobjects.RehabilitationContext;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RehabilitationContextServiceTest {
    final UUID id = new UUID(0, 1);
    final Instant now = Instant.EPOCH.plusSeconds(100);
    RehabilitationContextStore.Snapshot saved = new RehabilitationContextStore.Snapshot(id,
            RehabilitationContext.rehydrate(null, null, List.of(), null), 4, Instant.EPOCH);
    int writes;
    boolean assigned = true, revoked, race, unavailable;
    final RehabilitationContextStore store = new RehabilitationContextStore() {
        public Optional<Snapshot> readAssigned(UUID patient, String doctor) {
            if (unavailable) throw new IllegalStateException("private storage error");
            return assigned && id.equals(patient) && "doctor".equals(doctor) ? Optional.of(saved) : Optional.empty();
        }
        public Optional<Snapshot> replaceAssigned(UUID patient, String doctor, long version,
                RehabilitationContext value, Instant time) {
            if (revoked) return Optional.empty();
            if (race) throw new RehabilitationContextFailure(RehabilitationContextFailure.Kind.CONFLICT);
            assertEquals(saved.version(), version);
            assertEquals(now, time);
            if (!saved.context().equals(value)) {
                writes++;
                saved = new Snapshot(id, value, saved.version() + 1, time);
            }
            return Optional.of(saved);
        }
    };
    RehabilitationContextService service() {
        return new RehabilitationContextService(store, Clock.fixed(now, ZoneOffset.UTC));
    }
    RehabilitationContext.Patch notes(String value) {
        return new RehabilitationContext.Patch(absent(), absent(), absent(), set(value));
    }
    void failure(RehabilitationContextFailure.Kind expected, org.junit.jupiter.api.function.Executable action) {
        var before = saved;
        assertEquals(expected, assertThrows(RehabilitationContextFailure.class, action).kind());
        assertEquals(before, saved);
        assertEquals(0, writes);
    }

    @Test void assignedDoctorCanReadPatchAndReadBackWithoutExternalProfileCalls() {
        assertEquals(saved, service().read(id, "doctor"));
        var updated = service().patch(id, "doctor", 4, notes("manual"));
        assertEquals("manual", updated.context().observations());
        assertEquals(5, updated.version());
        assertEquals(now, updated.updatedAt());
        assertEquals(updated, service().read(id, "doctor"));
        assertEquals(1, writes);
    }
    @Test void unknownAndUnassignedHaveTheSameDenialAndNoWrite() {
        failure(RehabilitationContextFailure.Kind.FORBIDDEN, () -> service().read(new UUID(0, 2), "doctor"));
        failure(RehabilitationContextFailure.Kind.FORBIDDEN, () -> service().patch(id, "other", 4, notes("x")));
        assigned = false;
        failure(RehabilitationContextFailure.Kind.FORBIDDEN, () -> service().patch(id, "doctor", 4, notes("x")));
    }
    @Test void staleInvalidAndRevokedRequestsDoNotWrite() {
        failure(RehabilitationContextFailure.Kind.CONFLICT, () -> service().patch(id, "doctor", 3, notes("x")));
        failure(RehabilitationContextFailure.Kind.INVALID_REQUEST, () -> service().patch(id, "doctor", 4, notes("x".repeat(2001))));
        revoked = true;
        failure(RehabilitationContextFailure.Kind.FORBIDDEN, () -> service().patch(id, "doctor", 4, notes("x")));
    }
    @Test void raceIsAConflictAndNoOpKeepsTimeAndVersion() {
        failure(RehabilitationContextFailure.Kind.CONFLICT, () -> service().patch(id, "doctor", 3, notes(null)));
        assertEquals(saved, service().patch(id, "doctor", 4, notes(null)));
        assertEquals(0, writes);
        race = true;
        failure(RehabilitationContextFailure.Kind.CONFLICT, () -> service().patch(id, "doctor", 4, notes("x")));
    }
    @Test void missingPersistenceFailsClosedWithNoPrivateDiagnostic() {
        unavailable = true;
        var error = assertThrows(RehabilitationContextFailure.class, () -> service().read(id, "doctor"));
        assertEquals(RehabilitationContextFailure.Kind.UNAVAILABLE, error.kind());
        assertFalse(error.getMessage().contains("private"));
        assertEquals(0, writes);
    }
}
