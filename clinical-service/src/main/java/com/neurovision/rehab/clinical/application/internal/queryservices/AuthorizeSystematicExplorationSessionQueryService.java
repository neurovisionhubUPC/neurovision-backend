package com.neurovision.rehab.clinical.application.internal.queryservices;

import com.neurovision.rehab.clinical.application.ports.out.authorization.VisualSearchAuthorizationSnapshotRepository;
import com.neurovision.rehab.clinical.domain.model.valueobjects.*;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Read-only Systematic Exploration policy using the existing mode-neutral session snapshot reader. */
public final class AuthorizeSystematicExplorationSessionQueryService {
    private final VisualSearchAuthorizationSnapshotRepository snapshots;
    private final Clock clock;
    public AuthorizeSystematicExplorationSessionQueryService(VisualSearchAuthorizationSnapshotRepository snapshots, Clock clock) {
        this.snapshots = Objects.requireNonNull(snapshots); this.clock = Objects.requireNonNull(clock);
    }

    public Permit authorize(String authenticatedSubject, TherapySessionId sessionId) {
        if (sessionId == null) throw new SecurityException();
        final CognitoSubject subject;
        try { subject = new CognitoSubject(authenticatedSubject); }
        catch (RuntimeException invalid) { throw new SecurityException(); }
        if (!subject.value().equals(authenticatedSubject)) throw new SecurityException();
        final Optional<VisualSearchAuthorizationSnapshotRepository.Snapshot> observation;
        try { observation = snapshots.findBySubjectAndSession(subject, sessionId); }
        catch (RuntimeException unavailable) { throw unavailable(); }
        if (observation == null) throw unavailable();
        var snapshot = observation.orElseThrow(SecurityException::new);
        if (!snapshot.sessionId().equals(sessionId)) throw unavailable();
        var frozen = snapshot.configuration();
        if (frozen.exercise() != TherapeuticExercise.VISUAL_SCANNING) throw new SecurityException();
        var parameters = frozen.parameters();
        if (parameters instanceof TherapeuticExerciseParameters.Prescribed prescribed) parameters = prescribed.parameters();
        if (!(parameters instanceof TherapeuticExerciseParameters.VisualScanning scanning)) throw unavailable();
        if (scanning.mode() != TherapeuticExerciseParameters.VisualScanningMode.SYSTEMATIC_EXPLORATION)
            throw new SecurityException();
        boolean mayInsert = snapshot.status() == TherapySessionStatus.ACTIVE && snapshot.leaseExpiresAt() != null
                && clock.instant().isBefore(snapshot.leaseExpiresAt());
        return new Permit(sessionId.value(), frozen.configurationId().value(), frozen.configurationVersion(), scanning.minimumFixationMs(), scanning.maximumStimulusDurationMs(), mayInsert);
    }

    private static IllegalStateException unavailable() { return new IllegalStateException("Systematic Exploration authorization unavailable"); }

    public record Permit(UUID therapySessionId, UUID configurationId, long configurationVersion,
            int minimumFixationMs, int maximumStimulusDurationMs, boolean mayInsert) {
        @Override public String toString() { return "SystematicExplorationAuthorizationPermit[redacted]"; }
    }
}
