package com.neurovision.rehab.clinical.application.internal.queryservices;

import com.neurovision.rehab.clinical.application.ports.out.integration.*;
import com.neurovision.rehab.clinical.application.ports.out.persistence.DoctorTherapySessionReadRepository.Detail;
import com.neurovision.rehab.clinical.domain.model.valueobjects.CognitoSubject;
import java.time.Clock;
import java.util.*;

/** Internal composition: the report-preview boundary authenticates a DOCTOR and supplies
 * that subject and the original access token. This class does not verify OIDC or a token-subject binding.
 * Registered by SessionReportServicesConfiguration for that authenticated boundary.
 */
public final class ComposeSessionReportService {
    private final DoctorTherapySessionQueryService sessions;
    private final AssignedPatientQueryService patients;
    private final AssignedPatientNamesPort names;
    private final DoctorReportIdentityPort doctors;
    private final SessionReportAnalyticsPort analytics;
    private final Clock clock;
    private final SessionReportVisualSearchPort visualSearch;
    private final SessionReportStandardScanningPort standardScanning;
    private final SessionReportSustainedFixationPort sustainedFixation;
    private final SessionReportCompensatorySaccadePort compensatorySaccade;
    private final SessionReportSystematicExplorationPort systematicExploration;

    public ComposeSessionReportService(DoctorTherapySessionQueryService sessions, AssignedPatientQueryService patients,
            AssignedPatientNamesPort names, DoctorReportIdentityPort doctors, SessionReportAnalyticsPort analytics, Clock clock) {
        this(sessions,patients,names,doctors,analytics,(s,o,t) -> { throw unavailable(); },clock);
    }
    public ComposeSessionReportService(DoctorTherapySessionQueryService sessions, AssignedPatientQueryService patients,
            AssignedPatientNamesPort names, DoctorReportIdentityPort doctors, SessionReportAnalyticsPort analytics,
            SessionReportVisualSearchPort visualSearch, Clock clock) {
        this(sessions,patients,names,doctors,analytics,visualSearch,(s,o,t) -> { throw unavailable(); },clock);
    }
    public ComposeSessionReportService(DoctorTherapySessionQueryService sessions, AssignedPatientQueryService patients,
            AssignedPatientNamesPort names, DoctorReportIdentityPort doctors, SessionReportAnalyticsPort analytics,
            SessionReportVisualSearchPort visualSearch, SessionReportStandardScanningPort standardScanning, Clock clock) {
        this(sessions,patients,names,doctors,analytics,visualSearch,standardScanning,(s,o,t) -> { throw unavailable(); },clock);
    }
    public ComposeSessionReportService(DoctorTherapySessionQueryService sessions, AssignedPatientQueryService patients,
            AssignedPatientNamesPort names, DoctorReportIdentityPort doctors, SessionReportAnalyticsPort analytics,
            SessionReportVisualSearchPort visualSearch, SessionReportStandardScanningPort standardScanning,
            SessionReportSustainedFixationPort sustainedFixation, Clock clock) {
        this(sessions,patients,names,doctors,analytics,visualSearch,standardScanning,sustainedFixation,(s,o,t) -> { throw unavailable(); },clock);
    }
    public ComposeSessionReportService(DoctorTherapySessionQueryService sessions, AssignedPatientQueryService patients,
            AssignedPatientNamesPort names, DoctorReportIdentityPort doctors, SessionReportAnalyticsPort analytics,
            SessionReportVisualSearchPort visualSearch, SessionReportStandardScanningPort standardScanning,
            SessionReportSustainedFixationPort sustainedFixation, SessionReportCompensatorySaccadePort compensatorySaccade, Clock clock) {
        this(sessions,patients,names,doctors,analytics,visualSearch,standardScanning,sustainedFixation,compensatorySaccade,
                (s,o,t) -> { throw unavailable(); },clock);
    }
    public ComposeSessionReportService(DoctorTherapySessionQueryService sessions, AssignedPatientQueryService patients,
            AssignedPatientNamesPort names, DoctorReportIdentityPort doctors, SessionReportAnalyticsPort analytics,
            SessionReportVisualSearchPort visualSearch, SessionReportStandardScanningPort standardScanning,
            SessionReportSustainedFixationPort sustainedFixation, SessionReportCompensatorySaccadePort compensatorySaccade,
            SessionReportSystematicExplorationPort systematicExploration, Clock clock) {
        this.systematicExploration = Objects.requireNonNull(systematicExploration);
        this.compensatorySaccade = Objects.requireNonNull(compensatorySaccade);
        this.sustainedFixation = Objects.requireNonNull(sustainedFixation);
        this.standardScanning = Objects.requireNonNull(standardScanning);
        this.visualSearch = Objects.requireNonNull(visualSearch);
        this.sessions = Objects.requireNonNull(sessions); this.patients = Objects.requireNonNull(patients);
        this.names = Objects.requireNonNull(names); this.doctors = Objects.requireNonNull(doctors);
        this.analytics = Objects.requireNonNull(analytics); this.clock = Objects.requireNonNull(clock);
    }
    public SessionReportV1 compose(String authenticatedDoctorSubject, String originalAccessToken, UUID patient, UUID session) {
        return compose(authenticatedDoctorSubject,originalAccessToken,patient,session,null);
    }
    public SessionReportV1 compose(String authenticatedDoctorSubject, String originalAccessToken, UUID patient, UUID session,
            SessionReportVisualSearchPort.Origin origin) {
        return compose(authenticatedDoctorSubject,originalAccessToken,patient,session,origin,null);
    }
    public SessionReportV1 compose(String authenticatedDoctorSubject, String originalAccessToken, UUID patient, UUID session,
            SessionReportVisualSearchPort.Origin origin, SessionReportStandardScanningPort.Origin scanningOrigin) {
        return compose(authenticatedDoctorSubject,originalAccessToken,patient,session,origin,scanningOrigin,null);
    }
    public SessionReportV1 compose(String authenticatedDoctorSubject, String originalAccessToken, UUID patient, UUID session,
            SessionReportVisualSearchPort.Origin origin, SessionReportStandardScanningPort.Origin scanningOrigin,
            SessionReportSustainedFixationPort.Origin fixationOrigin) {
        return compose(authenticatedDoctorSubject,originalAccessToken,patient,session,origin,scanningOrigin,fixationOrigin,null);
    }
    public SessionReportV1 compose(String authenticatedDoctorSubject, String originalAccessToken, UUID patient, UUID session,
            SessionReportVisualSearchPort.Origin origin, SessionReportStandardScanningPort.Origin scanningOrigin,
            SessionReportSustainedFixationPort.Origin fixationOrigin, SessionReportCompensatorySaccadePort.Origin saccadeOrigin) {
        return compose(authenticatedDoctorSubject,originalAccessToken,patient,session,origin,scanningOrigin,fixationOrigin,saccadeOrigin,null);
    }
    public SessionReportV1 compose(String authenticatedDoctorSubject, String originalAccessToken, UUID patient, UUID session,
            SessionReportVisualSearchPort.Origin origin, SessionReportStandardScanningPort.Origin scanningOrigin,
            SessionReportSustainedFixationPort.Origin fixationOrigin, SessionReportCompensatorySaccadePort.Origin saccadeOrigin,
            SessionReportSystematicExplorationPort.Origin explorationOrigin) {
        if ((origin != null && scanningOrigin != null)
                || (fixationOrigin != null && (origin != null || scanningOrigin != null))
                || (saccadeOrigin != null && (origin != null || scanningOrigin != null || fixationOrigin != null))
                || (explorationOrigin != null && (origin != null || scanningOrigin != null || fixationOrigin != null || saccadeOrigin != null)))
            throw new PatientReadFailure(PatientReadFailure.Kind.INVALID_REQUEST);
        if (!new CognitoSubject(authenticatedDoctorSubject).value().equals(authenticatedDoctorSubject)
                || originalAccessToken == null || originalAccessToken.isBlank() || patient == null || session == null)
            throw new IllegalArgumentException("Invalid session report request");
        final Detail detail;
        try {
            // One Clinical observation enforces author AND current ACTIVE assignment; no list prequery.
            detail = sessions.detail(authenticatedDoctorSubject,patient,session);
        } catch (SecurityException denied) {
            throw new SecurityException("Session report access denied");
        } catch (RuntimeException unavailable) {
            throw unavailable();
        }
        final SessionReportV1.Execution execution;
        final SessionReportV1.FrozenConfiguration configuration;
        try {
            execution = SessionReportV1.execution(detail);
            configuration = SessionReportV1.configuration(detail);
        } catch (RuntimeException invalidStoredSession) { throw unavailable(); }
        if (explorationOrigin != null && SessionReportV1.systematicExplorationParameters(configuration.parameters()) == null)
            throw new PatientReadFailure(PatientReadFailure.Kind.INVALID_REQUEST);
        if (saccadeOrigin != null && SessionReportV1.compensatorySaccadeParameters(configuration.parameters()) == null)
            throw new PatientReadFailure(PatientReadFailure.Kind.INVALID_REQUEST);
        if (fixationOrigin != null && SessionReportV1.sustainedFixationParameters(configuration.parameters()) == null)
            throw new PatientReadFailure(PatientReadFailure.Kind.INVALID_REQUEST);
        if (scanningOrigin != null && !SessionReportV1.supportsStandardScanning(configuration.parameters()))
            throw new PatientReadFailure(PatientReadFailure.Kind.INVALID_REQUEST);
        if (origin != null && !SessionReportV1.supportsVisualSearch(configuration.parameters()))
            throw new PatientReadFailure(PatientReadFailure.Kind.INVALID_REQUEST);
        try {
            // Session is an unvalidated persistence read record: reject malformed/unfinished data before enrichment.
            var authorizedTargets = patients.authorizeNames(authenticatedDoctorSubject,List.of(patient));
            var resolvedNames = Objects.requireNonNull(names.lookup(authorizedTargets,originalAccessToken));
            if (!resolvedNames.keySet().equals(Set.of(patient))) throw unavailable();
            var patientName = Objects.requireNonNull(resolvedNames.get(patient));
            var doctor = Objects.requireNonNull(doctors.lookup(authenticatedDoctorSubject,originalAccessToken));
            if (!authenticatedDoctorSubject.equals(doctor.doctorSubject())) throw unavailable();
            var observation = Objects.requireNonNull(analytics.observe(session,originalAccessToken));
            if (!session.equals(observation.therapySessionId())) throw unavailable();
            SessionReportVisualSearchPort.Summary selected = null;
            if (origin != null) {
                selected = Objects.requireNonNull(visualSearch.observe(session,origin,originalAccessToken));
                if (selected.dataOrigin() != origin || !session.equals(selected.therapySessionId())
                        || !configuration.configurationId().equals(selected.configurationId())
                        || configuration.configurationVersion() != selected.configurationVersion()) throw unavailable();
            }
            SessionReportStandardScanningPort.Summary scanning = null;
            if (scanningOrigin != null) {
                scanning = Objects.requireNonNull(standardScanning.observe(session,scanningOrigin,originalAccessToken));
                if (scanning.dataOrigin() != scanningOrigin || !session.equals(scanning.therapySessionId())
                        || !configuration.configurationId().equals(scanning.configurationId())
                        || configuration.configurationVersion() != scanning.configurationVersion()) throw unavailable();
            }
            SessionReportSustainedFixationPort.Summary fixation = null;
            if (fixationOrigin != null) {
                fixation = Objects.requireNonNull(sustainedFixation.observe(session,fixationOrigin,originalAccessToken));
                if (fixation.dataOrigin() != fixationOrigin || !session.equals(fixation.therapySessionId())
                        || !configuration.configurationId().equals(fixation.configurationId())
                        || configuration.configurationVersion() != fixation.configurationVersion()) throw unavailable();
            }
            SessionReportCompensatorySaccadePort.Summary saccade = null;
            if (saccadeOrigin != null) {
                saccade = Objects.requireNonNull(compensatorySaccade.observe(session,saccadeOrigin,originalAccessToken));
                if (saccade.dataOrigin() != saccadeOrigin || !session.equals(saccade.therapySessionId())
                        || !configuration.configurationId().equals(saccade.configurationId())
                        || configuration.configurationVersion() != saccade.configurationVersion()) throw unavailable();
            }
            SessionReportSystematicExplorationPort.Summary exploration = null;
            if (explorationOrigin != null) {
                exploration = Objects.requireNonNull(systematicExploration.observe(session,explorationOrigin,originalAccessToken));
                if (exploration.dataOrigin() != explorationOrigin || !session.equals(exploration.therapySessionId())
                        || !configuration.configurationId().equals(exploration.configurationId())
                        || configuration.configurationVersion() != exploration.configurationVersion()) throw unavailable();
            }
            return new SessionReportV1(patient,doctor,patientName,execution,configuration,detail.sessionLimits(),
                    detail.technicalProgress(),observation,clock.instant(),selected,scanning,fixation,saccade,exploration);
        } catch (RuntimeException unavailable) {
            // Never expose a dependency message/cause containing clinical data or credentials.
            throw unavailable();
        }
    }
    private static IllegalStateException unavailable() {
        return new IllegalStateException("Session report composition unavailable");
    }
}
