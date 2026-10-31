package com.neurovision.rehab.clinical.application.internal.queryservices;

import com.neurovision.rehab.clinical.application.ports.out.persistence.SessionReportSnapshotRepository;
import com.neurovision.rehab.clinical.application.ports.out.persistence.SessionReportSnapshotRepository.Snapshot;
import com.neurovision.rehab.clinical.domain.model.valueobjects.CognitoSubject;
import java.util.*;

/** Reads immutable stored content using only current Clinical authorization, never enrichment peers. */
public final class GetSessionReportSnapshotService {
    private final SessionReportSnapshotRepository copies;
    public GetSessionReportSnapshotService(SessionReportSnapshotRepository copies) { this.copies = Objects.requireNonNull(copies); }

    public Optional<Snapshot> get(String doctor, UUID patient, UUID session, UUID id) {
        try {
            if (!new CognitoSubject(doctor).value().equals(doctor) || patient == null || session == null || id == null)
                throw new IllegalArgumentException();
        } catch (RuntimeException invalid) { throw new IllegalArgumentException("Invalid session report snapshot request"); }
        try {
            var observed = Objects.requireNonNull(copies.observeById(doctor, patient, session, id));
            if (!observed.authorized()) throw new SecurityException();
            observed.snapshot().ifPresent(copy -> {
                if (!id.equals(copy.id()) || !patient.equals(copy.clinicalPatientId()) || !session.equals(copy.therapySessionId()))
                    throw new IllegalStateException();
            });
            return observed.snapshot();
        } catch (SecurityException denied) { throw new SecurityException("Session report snapshot access denied"); }
        catch (RuntimeException unavailable) { throw new IllegalStateException("Session report snapshot unavailable"); }
    }
}
