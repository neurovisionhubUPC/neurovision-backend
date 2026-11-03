package com.neurovision.rehab.clinical.application.internal.commandservices;

import com.neurovision.rehab.clinical.application.internal.queryservices.ComposeSessionReportService;
import com.neurovision.rehab.clinical.application.ports.out.integration.SessionReportSnapshotEncoder;
import com.neurovision.rehab.clinical.application.ports.out.integration.SessionReportVisualSearchPort.Origin;
import com.neurovision.rehab.clinical.application.internal.queryservices.PatientReadFailure;
import com.neurovision.rehab.clinical.application.ports.out.persistence.SessionReportSnapshotRepository;
import com.neurovision.rehab.clinical.application.ports.out.persistence.SessionReportSnapshotRepository.*;
import com.neurovision.rehab.clinical.domain.model.valueobjects.*;
import java.util.*;

/** Manual copies only: enrichment happens outside the repository's short write transaction. */
public final class CreateSessionReportSnapshotService {
    private final SessionReportSnapshotRepository copies;
    private final ComposeSessionReportService composition;
    private final SessionReportSnapshotEncoder encoder;

    public CreateSessionReportSnapshotService(SessionReportSnapshotRepository copies,
            ComposeSessionReportService composition, SessionReportSnapshotEncoder encoder) {
        this.copies = Objects.requireNonNull(copies);
        this.composition = Objects.requireNonNull(composition);
        this.encoder = Objects.requireNonNull(encoder);
    }

    public WriteResult create(String doctor, String originalAccessToken, UUID patient, UUID session, String rawKey) {
        return create(doctor,originalAccessToken,patient,session,rawKey,null);
    }
    public WriteResult create(String doctor, String originalAccessToken, UUID patient, UUID session, String rawKey, Origin origin) {
        final String key;
        try {
            if (!new CognitoSubject(doctor).value().equals(doctor) || originalAccessToken == null
                    || originalAccessToken.isBlank() || patient == null || session == null) throw new IllegalArgumentException();
            key = new IdempotencyKey(rawKey).value();
        } catch (RuntimeException invalid) { throw new IllegalArgumentException("Invalid session report snapshot request"); }
        try {
            var observed = Objects.requireNonNull(copies.observeByKey(doctor, patient, session, key));
            if (!observed.authorized()) throw new SecurityException();
            if (observed.snapshot().isPresent()) return new WriteResult(false, selected(observed.snapshot().get(), patient, session, origin));
            var report = composition.compose(doctor, originalAccessToken, patient, session, origin);
            var candidate = new Snapshot(UUID.randomUUID(), patient, session, report.issuedAt(), encoder.encode(report));
            var result = Objects.requireNonNull(copies.insertAuthorized(doctor, patient, session, key, candidate));
            selected(result.snapshot(), patient, session, origin);
            return result;
        } catch (SessionReportSnapshotSelectionConflictException conflict) { throw conflict; }
        catch (PatientReadFailure failure) { throw failure; }
        catch (SecurityException denied) { throw new SecurityException("Session report snapshot access denied"); }
        catch (RuntimeException unavailable) { throw new IllegalStateException("Session report snapshot unavailable"); }
    }

    private Snapshot selected(Snapshot copy, UUID patient, UUID session, Origin origin) {
        bound(copy,patient,session);
        if (encoder.visualSearchSelection(copy.reportJson(),session) != origin) throw new SessionReportSnapshotSelectionConflictException();
        return copy;
    }
    private static Snapshot bound(Snapshot copy, UUID patient, UUID session) {
        if (!patient.equals(copy.clinicalPatientId()) || !session.equals(copy.therapySessionId()))
            throw new IllegalStateException();
        return copy;
    }
}
