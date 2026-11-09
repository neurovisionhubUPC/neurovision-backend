package com.neurovision.rehab.clinical.application.internal.commandservices;

import com.neurovision.rehab.clinical.application.internal.queryservices.ComposeSessionReportService;
import com.neurovision.rehab.clinical.application.ports.out.integration.SessionReportSnapshotEncoder;
import com.neurovision.rehab.clinical.application.ports.out.integration.SessionReportVisualSearchPort.Origin;
import com.neurovision.rehab.clinical.application.internal.queryservices.PatientReadFailure;
import com.neurovision.rehab.clinical.application.ports.out.persistence.SessionReportSnapshotRepository;
import com.neurovision.rehab.clinical.application.ports.out.persistence.SessionReportSnapshotRepository.*;
import com.neurovision.rehab.clinical.domain.model.valueobjects.*;
import com.neurovision.rehab.clinical.application.ports.out.integration.SessionReportSustainedFixationPort;
import com.neurovision.rehab.clinical.application.ports.out.integration.SessionReportCompensatorySaccadePort;
import com.neurovision.rehab.clinical.application.ports.out.integration.SessionReportSystematicExplorationPort;
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
        return create(doctor,originalAccessToken,patient,session,rawKey,origin,null);
    }
    public WriteResult create(String doctor, String originalAccessToken, UUID patient, UUID session, String rawKey, Origin origin,
            com.neurovision.rehab.clinical.application.ports.out.integration.SessionReportStandardScanningPort.Origin scanningOrigin) {
        return create(doctor,originalAccessToken,patient,session,rawKey,origin,scanningOrigin,null);
    }
    public WriteResult create(String doctor, String originalAccessToken, UUID patient, UUID session, String rawKey, Origin origin,
            com.neurovision.rehab.clinical.application.ports.out.integration.SessionReportStandardScanningPort.Origin scanningOrigin,
            SessionReportSustainedFixationPort.Origin fixationOrigin) {
        return create(doctor,originalAccessToken,patient,session,rawKey,origin,scanningOrigin,fixationOrigin,null);
    }
    public WriteResult create(String doctor, String originalAccessToken, UUID patient, UUID session, String rawKey, Origin origin,
            com.neurovision.rehab.clinical.application.ports.out.integration.SessionReportStandardScanningPort.Origin scanningOrigin,
            SessionReportSustainedFixationPort.Origin fixationOrigin, SessionReportCompensatorySaccadePort.Origin saccadeOrigin) {
        return create(doctor,originalAccessToken,patient,session,rawKey,origin,scanningOrigin,fixationOrigin,saccadeOrigin,null);
    }
    public WriteResult create(String doctor, String originalAccessToken, UUID patient, UUID session, String rawKey, Origin origin,
            com.neurovision.rehab.clinical.application.ports.out.integration.SessionReportStandardScanningPort.Origin scanningOrigin,
            SessionReportSustainedFixationPort.Origin fixationOrigin, SessionReportCompensatorySaccadePort.Origin saccadeOrigin,
            SessionReportSystematicExplorationPort.Origin explorationOrigin) {
        if ((origin != null && scanningOrigin != null)
                || (fixationOrigin != null && (origin != null || scanningOrigin != null))
                || (saccadeOrigin != null && (origin != null || scanningOrigin != null || fixationOrigin != null))
                || (explorationOrigin != null && (origin != null || scanningOrigin != null || fixationOrigin != null || saccadeOrigin != null)))
            throw new PatientReadFailure(PatientReadFailure.Kind.INVALID_REQUEST);
        final String key;
        try {
            if (!new CognitoSubject(doctor).value().equals(doctor) || originalAccessToken == null
                    || originalAccessToken.isBlank() || patient == null || session == null) throw new IllegalArgumentException();
            key = new IdempotencyKey(rawKey).value();
        } catch (RuntimeException invalid) { throw new IllegalArgumentException("Invalid session report snapshot request"); }
        try {
            var observed = Objects.requireNonNull(copies.observeByKey(doctor, patient, session, key));
            if (!observed.authorized()) throw new SecurityException();
            if (observed.snapshot().isPresent()) return new WriteResult(false, selected(observed.snapshot().get(), patient, session, origin, scanningOrigin, fixationOrigin, saccadeOrigin, explorationOrigin));
            var report = composition.compose(doctor, originalAccessToken, patient, session, origin, scanningOrigin, fixationOrigin, saccadeOrigin, explorationOrigin);
            var candidate = new Snapshot(UUID.randomUUID(), patient, session, report.issuedAt(), encoder.encode(report));
            var result = Objects.requireNonNull(copies.insertAuthorized(doctor, patient, session, key, candidate));
            selected(result.snapshot(), patient, session, origin, scanningOrigin, fixationOrigin, saccadeOrigin, explorationOrigin);
            return result;
        } catch (SessionReportSnapshotSelectionConflictException conflict) { throw conflict; }
        catch (PatientReadFailure failure) { throw failure; }
        catch (SecurityException denied) { throw new SecurityException("Session report snapshot access denied"); }
        catch (RuntimeException unavailable) { throw new IllegalStateException("Session report snapshot unavailable"); }
    }

    private Snapshot selected(Snapshot copy, UUID patient, UUID session, Origin origin,
            com.neurovision.rehab.clinical.application.ports.out.integration.SessionReportStandardScanningPort.Origin scanningOrigin,
            SessionReportSustainedFixationPort.Origin fixationOrigin, SessionReportCompensatorySaccadePort.Origin saccadeOrigin,
            SessionReportSystematicExplorationPort.Origin explorationOrigin) {
        bound(copy,patient,session);
        var savedFixation = encoder.sustainedFixationSelection(copy.reportJson(),session);
        if (encoder.visualSearchSelection(copy.reportJson(),session) != origin) throw new SessionReportSnapshotSelectionConflictException();
        if (encoder.standardScanningSelection(copy.reportJson(),session) != scanningOrigin) throw new SessionReportSnapshotSelectionConflictException();
        if (savedFixation != fixationOrigin) throw new SessionReportSnapshotSelectionConflictException();
        if (encoder.compensatorySaccadeSelection(copy.reportJson(),session) != saccadeOrigin)
            throw new SessionReportSnapshotSelectionConflictException();
        if (encoder.systematicExplorationSelection(copy.reportJson(),session) != explorationOrigin)
            throw new SessionReportSnapshotSelectionConflictException();
        return copy;
    }
    private static Snapshot bound(Snapshot copy, UUID patient, UUID session) {
        if (!patient.equals(copy.clinicalPatientId()) || !session.equals(copy.therapySessionId()))
            throw new IllegalStateException();
        return copy;
    }
}
