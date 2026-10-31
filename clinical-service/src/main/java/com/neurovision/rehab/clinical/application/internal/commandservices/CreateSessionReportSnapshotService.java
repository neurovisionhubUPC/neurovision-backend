package com.neurovision.rehab.clinical.application.internal.commandservices;

import com.neurovision.rehab.clinical.application.internal.queryservices.ComposeSessionReportService;
import com.neurovision.rehab.clinical.application.ports.out.integration.SessionReportSnapshotEncoder;
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
        final String key;
        try {
            if (!new CognitoSubject(doctor).value().equals(doctor) || originalAccessToken == null
                    || originalAccessToken.isBlank() || patient == null || session == null) throw new IllegalArgumentException();
            key = new IdempotencyKey(rawKey).value();
        } catch (RuntimeException invalid) { throw new IllegalArgumentException("Invalid session report snapshot request"); }
        try {
            var observed = Objects.requireNonNull(copies.observeByKey(doctor, patient, session, key));
            if (!observed.authorized()) throw new SecurityException();
            if (observed.snapshot().isPresent()) return new WriteResult(false, bound(observed.snapshot().get(), patient, session));
            var report = composition.compose(doctor, originalAccessToken, patient, session);
            var candidate = new Snapshot(UUID.randomUUID(), patient, session, report.issuedAt(), encoder.encode(report));
            var result = Objects.requireNonNull(copies.insertAuthorized(doctor, patient, session, key, candidate));
            bound(result.snapshot(), patient, session);
            return result;
        } catch (SecurityException denied) { throw new SecurityException("Session report snapshot access denied"); }
        catch (RuntimeException unavailable) { throw new IllegalStateException("Session report snapshot unavailable"); }
    }

    private static Snapshot bound(Snapshot copy, UUID patient, UUID session) {
        if (!patient.equals(copy.clinicalPatientId()) || !session.equals(copy.therapySessionId()))
            throw new IllegalStateException();
        return copy;
    }
}
