package com.neurovision.rehab.clinical.infrastructure.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neurovision.rehab.clinical.application.internal.queryservices.*;
import com.neurovision.rehab.clinical.application.internal.commandservices.CreateSessionReportSnapshotService;
import com.neurovision.rehab.clinical.application.ports.out.integration.*;
import com.neurovision.rehab.clinical.application.ports.out.persistence.SessionReportSnapshotRepository;
import com.neurovision.rehab.clinical.application.ports.out.persistence.SessionReportSnapshotListRepository;
import com.neurovision.rehab.clinical.infrastructure.integration.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import java.net.URI;
import java.time.Clock;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Wires only the report ports; existing Clinical queries and patient-name beans remain shared. */
@ApplicationScoped
public class SessionReportServicesConfiguration {
    @Produces @ApplicationScoped
    ListSessionReportSnapshotsService listReportSnapshots(SessionReportSnapshotListRepository copies) {
        return new ListSessionReportSnapshotsService(copies);
    }

    @Produces @ApplicationScoped
    SessionReportPdfRenderer reportPdfRenderer(ObjectMapper mapper) {
        return new com.neurovision.rehab.clinical.infrastructure.reporting.PdfBoxSessionReportRenderer(mapper);
    }

    @Produces @ApplicationScoped
    DoctorReportIdentityPort doctorReportIdentity(
            @ConfigProperty(name = "clinical.profile.base-url") String base,
            @ConfigProperty(name = "clinical.profile.timeout-ms") long timeoutMs, ObjectMapper mapper) {
        return new ProfileDoctorReportIdentityAdapter(URI.create(base), timeoutMs, mapper);
    }

    @Produces @ApplicationScoped
    SessionReportAnalyticsPort sessionReportAnalytics(
            @ConfigProperty(name = "clinical.analytics.base-url") String base,
            @ConfigProperty(name = "clinical.analytics.timeout-ms") long timeoutMs, ObjectMapper mapper) {
        return new AnalyticsSessionReportAdapter(new ReportAnalyticsHttpClient(URI.create(base), timeoutMs, mapper));
    }

    @Produces @ApplicationScoped
    SessionReportVisualSearchPort sessionReportVisualSearch(
            @ConfigProperty(name = "clinical.analytics.base-url") String base,
            @ConfigProperty(name = "clinical.analytics.timeout-ms") long timeoutMs, ObjectMapper mapper) {
        return new AnalyticsVisualSearchReportAdapter(new ReportAnalyticsHttpClient(URI.create(base),timeoutMs,mapper));
    }

    @Produces @ApplicationScoped
    ComposeSessionReportService sessionReports(DoctorTherapySessionQueryService sessions,
            AssignedPatientQueryService patients, AssignedPatientNamesPort names,
            DoctorReportIdentityPort doctors, SessionReportAnalyticsPort analytics, SessionReportVisualSearchPort visualSearch) {
        return new ComposeSessionReportService(sessions, patients, names, doctors, analytics, visualSearch, Clock.systemUTC());
    }

    @Produces @ApplicationScoped
    SessionReportSnapshotEncoder snapshotEncoder(ObjectMapper mapper) {
        return new JsonSessionReportSnapshotEncoder(mapper);
    }

    @Produces @ApplicationScoped
    CreateSessionReportSnapshotService createReportSnapshots(SessionReportSnapshotRepository copies,
            ComposeSessionReportService composition, SessionReportSnapshotEncoder encoder) {
        return new CreateSessionReportSnapshotService(copies, composition, encoder);
    }

    @Produces @ApplicationScoped
    GetSessionReportSnapshotService getReportSnapshots(SessionReportSnapshotRepository copies) {
        return new GetSessionReportSnapshotService(copies);
    }
}
