package com.neurovision.rehab.clinical.application.internal.queryservices;

import com.neurovision.rehab.clinical.application.ports.out.integration.*;
import com.neurovision.rehab.clinical.application.ports.out.integration.DoctorReportIdentityPort.*;
import com.neurovision.rehab.clinical.application.ports.out.integration.SessionReportAnalyticsPort.*;
import com.neurovision.rehab.clinical.application.ports.out.persistence.*;
import com.neurovision.rehab.clinical.application.ports.out.persistence.DoctorTherapySessionReadRepository.*;
import com.neurovision.rehab.clinical.domain.model.valueobjects.*;
import com.neurovision.rehab.clinical.support.TherapeuticConfigurationTestData;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ComposeSessionReportServiceTest {
    static final UUID PATIENT = new UUID(0,1), SESSION = new UUID(0,2), PLAN = new UUID(0,3), CONFIG = new UUID(0,4);
    static final Instant START = Instant.parse("2026-10-01T00:00:00Z"), END = START.plusSeconds(120);
    static final String DOCTOR = "synthetic-doctor", TOKEN = "synthetic-token";
    final List<String> calls = new ArrayList<>();
    Detail detail = detail("COMPLETED", TherapeuticConfigurationTestData.fixationSnapshot().parameters());
    boolean author = true, activeAssignment = true, brokenDetail, brokenNames, brokenDoctor, brokenAnalytics;
    Map<UUID,String> targets = Map.of(PATIENT,"synthetic-patient");
    Map<UUID,AssignedPatientNamesPort.Name> names = Map.of(PATIENT,new AssignedPatientNamesPort.Name("AVAILABLE","Synthetic","Patient"));
    Identity doctor = new Identity(DOCTOR,Availability.AVAILABLE,"Synthetic","Doctor",null,null);
    SessionReportAnalyticsPort.Observation observation = new ConfirmedNoEvidence(SESSION);
    final DoctorTherapySessionQueryService sessions = new DoctorTherapySessionQueryService(new DoctorTherapySessionReadRepository() {
        public DoctorTherapySessionReadRepository.Observation observe(UUID p,String d,Filters f,int o,int l) { throw new AssertionError("No list authorization"); }
        public Optional<Detail> observeDetail(UUID p,String d,UUID s) {
            calls.add("detail"); assertEquals(PATIENT,p); assertEquals(DOCTOR,d); assertEquals(SESSION,s);
            if (brokenDetail) throw new IllegalStateException("private dependency data");
            return author && activeAssignment ? Optional.ofNullable(detail) : Optional.empty();
        }
    });
    final AssignedPatientNamesPort namePort = (requested,token) -> {
        calls.add("names"); assertEquals(targets,requested); assertEquals(TOKEN,token);
        if (brokenNames) throw new IllegalStateException("private profile data");
        return names;
    };
    final AssignedPatientQueryService patients = new AssignedPatientQueryService(new AssignedPatientReadPort() {
        public Page list(String d,int o,int l) { throw new AssertionError(); }
        public Optional<Patient> detail(String d,UUID p) { throw new AssertionError(); }
        public Map<UUID,String> resolve(String d,List<UUID> ids) {
            calls.add("resolve"); assertEquals(DOCTOR,d); assertEquals(List.of(PATIENT),ids); return targets;
        }
    },namePort);
    final ComposeSessionReportService service = new ComposeSessionReportService(sessions,patients,namePort,(d,t) -> {
        calls.add("doctor"); assertEquals(DOCTOR,d); assertEquals(TOKEN,t);
        if (brokenDoctor) throw new IllegalStateException("private doctor data"); return doctor;
    },(s,t) -> {
        calls.add("analytics"); assertEquals(SESSION,s); assertEquals(TOKEN,t);
        if (brokenAnalytics) throw new SecurityException("private analytics data"); return observation;
    },Clock.fixed(END.plusSeconds(60),ZoneOffset.UTC));

    static Detail detail(String status,TherapeuticExerciseParameters parameters) {
        var progress = parameters instanceof TherapeuticExerciseParameters.Prescribed p
                ? new TechnicalProgress(TechnicalProgress.Availability.AVAILABLE,p.completionTarget().criterion(),(long)p.completionTarget().target(),120_000L,10L,true)
                : TechnicalProgress.notApplicable();
        return new Detail(PATIENT,new Session(SESSION,PLAN,parameters.exercise().code().value(),status,START,
                "COMPLETED".equals(status)?END:null,120,LocalDate.of(2026,10,1),CONFIG,2,parameters.schemaVersion()),
                parameters,new SessionLimits(1,10,25),progress);
    }
    SessionReportV1 compose() { return service.compose(DOCTOR,TOKEN,PATIENT,SESSION); }
    @Test void composesConfirmedAbsenceAndForwardsOriginalTokenOnlyAfterAuthorization() {
        var report = compose();
        assertEquals(List.of("detail","resolve","names","doctor","analytics"),calls);
        assertEquals(PATIENT,report.clinicalPatientId()); assertEquals(SESSION,report.execution().therapySessionId());
        assertEquals(detail.configurationParameters(),report.configuration().parameters());
        assertEquals(detail.sessionLimits(),report.sessionLimits()); assertEquals(120,report.execution().activeDurationSeconds());
        assertEquals(END.plusSeconds(60),report.issuedAt()); assertSame(doctor,report.doctor());
        assertEquals("Sin evidencia analítica disponible",report.analyticsNotice());
        assertEquals(TechnicalProgress.Availability.NOT_APPLICABLE,report.technicalProgress().availability());
        assertNull(report.technicalProgress().activeMilliseconds()); assertNull(report.technicalProgress().target());
    }
    @Test void authorOrActiveAssignmentDenialNeverCallsExternalPorts() {
        author = false; assertThrows(SecurityException.class,this::compose); assertEquals(List.of("detail"),calls);
        calls.clear(); author = true; activeAssignment = false;
        assertThrows(SecurityException.class,this::compose); assertEquals(List.of("detail"),calls);
        calls.clear(); activeAssignment = true; targets = Map.of();
        assertThrows(RuntimeException.class,this::compose); assertEquals(List.of("detail","resolve"),calls);
    }
    @Test void completedGatePrecedesAllIdentityAndAnalyticsReads() {
        for (String status : List.of("ACTIVE","CANCELLED","ABANDONED","FAILED_TECHNICAL")) {
            calls.clear(); detail = detail(status,TherapeuticConfigurationTestData.fixationSnapshot().parameters());
            assertThrows(IllegalStateException.class,this::compose); assertEquals(List.of("detail"),calls);
        }
    }
    @Test void invalidActorAndTokenNeverRead() {
        for (String actor : Arrays.asList(null,""," "," synthetic-doctor"))
            assertThrows(IllegalArgumentException.class,()->service.compose(actor,TOKEN,PATIENT,SESSION));
        for (String token : Arrays.asList(null,""," "))
            assertThrows(IllegalArgumentException.class,()->service.compose(DOCTOR,token,PATIENT,SESSION));
        assertThrows(IllegalArgumentException.class,()->service.compose(DOCTOR,TOKEN,null,SESSION));
        assertThrows(IllegalArgumentException.class,()->service.compose(DOCTOR,TOKEN,PATIENT,null)); assertTrue(calls.isEmpty());
    }
    @Test void unavailableOrForeignStoredDetailsFailBeforeEnrichment() {
        brokenDetail = true; assertThrows(IllegalStateException.class,this::compose); assertEquals(List.of("detail"),calls);
        brokenDetail = false; calls.clear();
        var original = detail.session();
        detail = new Detail(PATIENT,new Session(new UUID(0,99),PLAN,"fixation","COMPLETED",START,END,120,
                original.quotaDate(),CONFIG,2,1),detail.configurationParameters(),detail.sessionLimits(),detail.technicalProgress());
        assertThrows(IllegalStateException.class,this::compose); assertEquals(List.of("detail"),calls);
    }
    @Test void malformedPersistedSessionFieldsFailBeforeExternalReads() {
        var original = detail;
        for (Session invalid : List.of(
                new Session(SESSION,null,"fixation","COMPLETED",START,END,120,LocalDate.of(2026,10,1),CONFIG,2,1),
                new Session(SESSION,PLAN,"fixation","COMPLETED",START,null,120,LocalDate.of(2026,10,1),CONFIG,2,1),
                new Session(SESSION,PLAN,"fixation","COMPLETED",START,START.minusSeconds(1),120,LocalDate.of(2026,10,1),CONFIG,2,1),
                new Session(SESSION,PLAN,"fixation","COMPLETED",START,END,-1,LocalDate.of(2026,10,1),CONFIG,2,1),
                new Session(SESSION,PLAN,"fixation","COMPLETED",START,END,120,LocalDate.of(2026,10,1),CONFIG,0,1))) {
            calls.clear(); detail = new Detail(PATIENT,invalid,original.configurationParameters(),original.sessionLimits(),original.technicalProgress());
            assertThrows(IllegalStateException.class,this::compose); assertEquals(List.of("detail"),calls);
        }
    }
    @Test void identityMissingAndPendingAreNotEligibilityFailures() {
        for (String status : List.of("MISSING","PENDING_COMPLETION")) {
            names = Map.of(PATIENT,new AssignedPatientNamesPort.Name(status,null,null));
            doctor = new Identity(DOCTOR,Availability.valueOf(status),null,null,null,null);
            assertEquals(status,compose().patient().status()); assertEquals(Availability.valueOf(status),compose().doctor().availability());
        }
    }
    @Test void identityKeyMismatchAndDoctorSubjectMismatchFailClosed() {
        names = Map.of(new UUID(0,99),new AssignedPatientNamesPort.Name("MISSING",null,null));
        assertThrows(IllegalStateException.class,this::compose); assertFalse(calls.contains("analytics"));
        names = Map.of(PATIENT,new AssignedPatientNamesPort.Name("MISSING",null,null)); calls.clear();
        doctor = new Identity("another-doctor",Availability.MISSING,null,null,null,null);
        assertThrows(IllegalStateException.class,this::compose); assertFalse(calls.contains("analytics"));
    }
    @Test void dependencyFailuresAndNullAreNotConvertedToAbsenceAndErrorsAreSanitized() {
        brokenNames = true; var failure = assertThrows(IllegalStateException.class,this::compose);
        assertNull(failure.getCause()); assertFalse(failure.toString().contains("private"));
        brokenNames = false; brokenDoctor = true; assertThrows(IllegalStateException.class,this::compose);
        brokenDoctor = false; brokenAnalytics = true; assertThrows(IllegalStateException.class,this::compose);
        brokenAnalytics = false; observation = null; assertThrows(IllegalStateException.class,this::compose);
        observation = new ConfirmedNoEvidence(new UUID(0,99)); assertThrows(IllegalStateException.class,this::compose);
        observation = new ConfirmedNoEvidence(SESSION); doctor = null; assertThrows(IllegalStateException.class,this::compose);
        doctor = new Identity(DOCTOR,Availability.MISSING,null,null,null,null); names = null;
        assertThrows(IllegalStateException.class,this::compose);
    }
    @Test void observedStreamsRemainIndependentAndReadWindowIsExplicit() {
        var streams = new ArrayList<>(List.of(SessionReportV1Test.stream(1,1),SessionReportV1Test.stream(2,7)));
        observation = new ObservedEvidence(SESSION,0,2,true,streams); streams.clear();
        var report = compose(); var evidence = (ObservedEvidence)report.analytics();
        assertNull(report.analyticsNotice()); assertTrue(evidence.hasNext()); assertEquals(2,evidence.streams().size());
        assertEquals(1,evidence.streams().getFirst().summary().source().revision());
        assertEquals(7,evidence.streams().getLast().summary().source().revision());
        assertTrue(evidence.streams().getFirst().summary().completeness().unknownSessionBoundary());
        assertTrue(evidence.streams().getFirst().gazePlot().hasNext());
        assertEquals(64,evidence.streams().getFirst().gazePlot().sourceBatchScanLimit());
    }
    @Test void commonModelCoversFiveFrozenModesWithoutSynthesizingGoals() {
        var fixation = TherapeuticConfigurationTestData.fixationSnapshot().parameters();
        var search = TherapeuticConfigurationTestData.visualSearchSnapshot().parameters();
        var modes = new HashSet<String>();
        for (var mode : TherapeuticExerciseParameters.VisualScanningMode.values()) {
            var parameters = new TherapeuticExerciseParameters.VisualScanning(mode,TherapeuticExerciseParameters.AffectedHemifield.LEFT,500,3000,10,50,10,800,2);
            detail = detail("COMPLETED",parameters); modes.add(compose().mode());
        }
        for (var parameters : List.of(fixation,search)) { detail = detail("COMPLETED",parameters); modes.add(compose().mode()); }
        assertEquals(Set.of("STANDARD_SCANNING","SYSTEMATIC_EXPLORATION","COMPENSATORY_SACCADE","TARGET_SEARCH_WITH_DISTRACTORS","SUSTAINED_TARGET_FIXATION"),modes);
        var prescribed = new TherapeuticExerciseParameters.Prescribed(fixation,new CompletionTarget(CompletionTarget.Criterion.ACTIVE_TIME,120));
        detail = detail("COMPLETED",prescribed); var report = compose();
        assertEquals(120_000,report.technicalProgress().activeMilliseconds()); assertEquals(120,report.technicalProgress().target());
        assertEquals(prescribed,report.configuration().parameters()); assertTrue(report.technicalProgress().targetReached());
    }
}
