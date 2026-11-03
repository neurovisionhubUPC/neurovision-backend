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

    public ComposeSessionReportService(DoctorTherapySessionQueryService sessions, AssignedPatientQueryService patients,
            AssignedPatientNamesPort names, DoctorReportIdentityPort doctors, SessionReportAnalyticsPort analytics, Clock clock) {
        this(sessions,patients,names,doctors,analytics,(s,o,t) -> { throw unavailable(); },clock);
    }
    public ComposeSessionReportService(DoctorTherapySessionQueryService sessions, AssignedPatientQueryService patients,
            AssignedPatientNamesPort names, DoctorReportIdentityPort doctors, SessionReportAnalyticsPort analytics,
            SessionReportVisualSearchPort visualSearch, Clock clock) {
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
            return new SessionReportV1(patient,doctor,patientName,execution,configuration,detail.sessionLimits(),
                    detail.technicalProgress(),observation,clock.instant(),selected);
        } catch (RuntimeException unavailable) {
            // Never expose a dependency message/cause containing clinical data or credentials.
            throw unavailable();
        }
    }
    private static IllegalStateException unavailable() {
        return new IllegalStateException("Session report composition unavailable");
    }
}
