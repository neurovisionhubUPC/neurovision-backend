package com.neurovision.rehab.clinical.application.internal.queryservices;

import com.neurovision.rehab.clinical.application.ports.out.integration.*;
import com.neurovision.rehab.clinical.application.ports.out.integration.SessionReportSystematicExplorationPort.*;
import com.neurovision.rehab.clinical.domain.model.valueobjects.TherapeuticExerciseParameters;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class ComposeSystematicExplorationReportServiceTest extends ComposeSessionReportServiceTest {
    Summary summary = summary(SESSION, CONFIG, 2, Origin.SYNTHETIC);
    boolean brokenExploration;
    static Summary summary(UUID session, UUID config, long version, Origin origin) {
        return new Summary("systematic-exploration-trial-v1", session, config, version, origin, 1, List.of(new Traversal("trial-1", Outcome.COMPLETED, 4, 100, 4000)));
    }
    ComposeSessionReportService selectedService() {
        return new ComposeSessionReportService(sessions, patients, namePort, (d,t) -> {
            calls.add("doctor"); assertEquals(TOKEN,t); return doctor;
        }, (s,t) -> { calls.add("analytics"); return observation; }, (s,o,t) -> { fail("Visual Search must not be called"); return null; }, (s,o,t) -> { fail("Standard Scanning must not be called"); return null; }, (s,o,t) -> { fail("Sustained Fixation must not be called"); return null; }, (s,o,t) -> { fail("Compensatory Saccade must not be called"); return null; }, (s,o,t) -> {
            calls.add("exploration"); assertEquals(SESSION,s); assertEquals(Origin.SYNTHETIC,o); assertEquals(TOKEN,t);
            if (brokenExploration) throw new SecurityException("private upstream denial"); return summary;
        }, Clock.fixed(END.plusSeconds(60), ZoneOffset.UTC));
    }
    SessionReportV1 selected() { return selectedService().compose(DOCTOR,TOKEN,PATIENT,SESSION,null,null,null,null,Origin.SYNTHETIC); }
    void exploration() { detail = detail("COMPLETED",systematicExplorationParameters()); }
    @Test void explicitSelectionPreservesMetricsAndOmissionNeverCallsNewPort() {
        exploration(); assertSame(summary,selected().systematicExploration());
        assertEquals(List.of("detail","resolve","names","doctor","analytics","exploration"),calls);
        calls.clear(); assertNull(selectedService().compose(DOCTOR,TOKEN,PATIENT,SESSION).systematicExploration());
        assertFalse(calls.contains("exploration"));
    }
    @Test void unsupportedModeRejectedAfterClinicalAuthorizationBeforeAnyEnrichment() {
        detail = detail("COMPLETED",ComposeStandardScanningReportServiceTest.standardScanningParameters());
        var failure = assertThrows(PatientReadFailure.class,this::selected);
        assertEquals(PatientReadFailure.Kind.INVALID_REQUEST,failure.kind()); assertEquals(List.of("detail"),calls);
        calls.clear(); author = false; assertThrows(SecurityException.class,this::selected); assertEquals(List.of("detail"),calls);
    }
    @Test void selectedFailureNullAndEveryBindingMismatchFailClosed() {
        exploration(); brokenExploration = true; assertThrows(IllegalStateException.class,this::selected);
        brokenExploration = false;
        for (var invalid : Arrays.asList(null,summary(new UUID(0,99),CONFIG,2,Origin.SYNTHETIC),
                summary(SESSION,new UUID(0,99),2,Origin.SYNTHETIC),summary(SESSION,CONFIG,3,Origin.SYNTHETIC),
                summary(SESSION,CONFIG,2,Origin.PRODUCER_REPORTED))) {
            summary = invalid; var error = assertThrows(IllegalStateException.class,this::selected);
            assertNull(error.getCause()); assertFalse(error.toString().contains("private"));
        }
    }
    @Test void prescribedExplorationUsesTheFrozenConfigurationAndBaselineFailuresRemainIndependent() {
        var parameters = new com.neurovision.rehab.clinical.domain.model.valueobjects.TherapeuticExerciseParameters.Prescribed(
                systematicExplorationParameters(),
                new com.neurovision.rehab.clinical.domain.model.valueobjects.CompletionTarget(
                        com.neurovision.rehab.clinical.domain.model.valueobjects.CompletionTarget.Criterion.ACTIVE_TIME,120));
        detail = detail("COMPLETED",parameters); assertSame(summary,selected().systematicExploration());
        calls.clear(); observation = null; assertThrows(IllegalStateException.class,this::selected); assertFalse(calls.contains("exploration"));
    }
    @Test void confirmedZeroRemainsASelectedSummaryWithEmptyTraversals() {
        exploration(); summary = new Summary("systematic-exploration-trial-v1",SESSION,CONFIG,2,Origin.SYNTHETIC,0,List.of());
        var report = selected(); assertNotNull(report.systematicExploration()); assertTrue(report.systematicExploration().traversals().isEmpty());
        assertEquals(0,report.systematicExploration().reportedTrialCount());
        assertEquals("Sin evidencia analítica disponible",report.analyticsNotice());
    }
    public static com.neurovision.rehab.clinical.domain.model.valueobjects.TherapeuticExerciseParameters systematicExplorationParameters() {
        return new TherapeuticExerciseParameters.VisualScanning(
                TherapeuticExerciseParameters.VisualScanningMode.SYSTEMATIC_EXPLORATION,
                TherapeuticExerciseParameters.AffectedHemifield.LEFT,500,3000,10,50,5,500,1);
    }

    @Test void elapsedOutsideFrozenWindowIsUnavailable() {
        exploration();
        for (var row : List.of(new Traversal("trial-1",Outcome.COMPLETED,4,100,1999),
                new Traversal("trial-1",Outcome.COMPLETED,4,100,12001),
                new Traversal("trial-1",Outcome.TIMED_OUT,0,0,2500),
                new Traversal("trial-1",Outcome.INTERRUPTED,0,0,3000))) {
            summary = new Summary("systematic-exploration-trial-v1",SESSION,CONFIG,2,Origin.SYNTHETIC,1,List.of(row));
            assertThrows(IllegalStateException.class,this::selected);
        }
    }

    @Test void combinedSelectionIsRejectedBeforeClinicalOrPeerReads() {
        exploration();
        for (int selected=0;selected<4;selected++) {
            final int index=selected;
            var failure = assertThrows(PatientReadFailure.class, () -> selectedService().compose(DOCTOR,TOKEN,PATIENT,SESSION,
                    index==0 ? SessionReportVisualSearchPort.Origin.SYNTHETIC : null,
                    index==1 ? SessionReportStandardScanningPort.Origin.SYNTHETIC : null,
                    index==2 ? SessionReportSustainedFixationPort.Origin.SYNTHETIC : null,
                    index==3 ? SessionReportCompensatorySaccadePort.Origin.SYNTHETIC : null,Origin.SYNTHETIC));
            assertEquals(PatientReadFailure.Kind.INVALID_REQUEST,failure.kind()); assertTrue(calls.isEmpty());
        }
    }
}
