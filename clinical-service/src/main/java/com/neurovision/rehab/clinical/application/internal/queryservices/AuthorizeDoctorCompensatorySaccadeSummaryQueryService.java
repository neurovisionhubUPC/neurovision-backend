package com.neurovision.rehab.clinical.application.internal.queryservices;

import com.neurovision.rehab.clinical.application.ports.out.authorization.DoctorVisualSearchSummarySnapshotRepository;
import com.neurovision.rehab.clinical.domain.model.valueobjects.*;
import java.util.Objects;
import java.util.UUID;

/** Read-only Compensatory Saccade policy using the existing mode-neutral DOCTOR snapshot reader. */
public final class AuthorizeDoctorCompensatorySaccadeSummaryQueryService {
    private final DoctorVisualSearchSummarySnapshotRepository snapshots;
    public AuthorizeDoctorCompensatorySaccadeSummaryQueryService(DoctorVisualSearchSummarySnapshotRepository snapshots) {
        this.snapshots = Objects.requireNonNull(snapshots);
    }

    public Permit authorize(String authenticatedDoctorSubject, TherapySessionId sessionId) {
        if (sessionId == null) throw new SecurityException();
        final CognitoSubject doctor;
        try { doctor = new CognitoSubject(authenticatedDoctorSubject); }
        catch (RuntimeException invalid) { throw new SecurityException(); }
        if (!doctor.value().equals(authenticatedDoctorSubject)) throw new SecurityException();
        final java.util.Optional<DoctorVisualSearchSummarySnapshotRepository.Snapshot> observation;
        try { observation = snapshots.findByDoctorAndSession(doctor, sessionId); }
        catch (RuntimeException unavailable) { throw unavailable(); }
        if (observation == null) throw unavailable();
        var snapshot = observation.orElseThrow(SecurityException::new);
        if (!snapshot.sessionId().equals(sessionId)) throw unavailable();
        var frozen = snapshot.configuration();
        if (frozen.exercise() != TherapeuticExercise.VISUAL_SCANNING) throw new SecurityException();
        var parameters = frozen.parameters();
        if (parameters instanceof TherapeuticExerciseParameters.Prescribed prescribed) parameters = prescribed.parameters();
        if (!(parameters instanceof TherapeuticExerciseParameters.VisualScanning scanning)) throw unavailable();
        if (scanning.mode() != TherapeuticExerciseParameters.VisualScanningMode.COMPENSATORY_SACCADE)
            throw new SecurityException();
        return new Permit(sessionId.value(), frozen.configurationId().value(), frozen.configurationVersion(),
                scanning.minimumFixationMs(), scanning.maximumStimulusDurationMs());
    }

    private static IllegalStateException unavailable() { return new IllegalStateException("Compensatory Saccade summary authorization unavailable"); }

    public record Permit(UUID therapySessionId, UUID configurationId, long configurationVersion,
            int minimumFixationMs, int maximumStimulusDurationMs) {
        @Override public String toString() { return "DoctorCompensatorySaccadeSummaryPermit[redacted]"; }
    }
}
