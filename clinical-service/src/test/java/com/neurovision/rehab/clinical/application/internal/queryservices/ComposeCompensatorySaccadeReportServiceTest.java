package com.neurovision.rehab.clinical.application.internal.queryservices;

import com.neurovision.rehab.clinical.application.ports.out.integration.*;
import com.neurovision.rehab.clinical.application.ports.out.integration.SessionReportCompensatorySaccadePort.*;
import com.neurovision.rehab.clinical.domain.model.valueobjects.TherapeuticExerciseParameters;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class ComposeCompensatorySaccadeReportServiceTest extends ComposeSessionReportServiceTest {
    Summary summary = summary(SESSION, CONFIG, 2, Origin.SYNTHETIC);
    boolean brokenSaccade;
    static Summary summary(UUID session, UUID config, long version, Origin origin) {
        return new Summary("compensatory-saccade-trial-v1", session, config, version, origin, 6, 3, new BigDecimal("1000.5"));
    }
    ComposeSessionReportService selectedService() {
        return new ComposeSessionReportService(sessions, patients, namePort, (d,t) -> {
            calls.add("doctor"); assertEquals(TOKEN,t); return doctor;
        }, (s,t) -> { calls.add("analytics"); return observation; }, (s,o,t) -> { fail("Visual Search must not be called"); return null; }, (s,o,t) -> { fail("Standard Scanning must not be called"); return null; }, (s,o,t) -> { fail("Sustained Fixation must not be called"); return null; }, (s,o,t) -> {
            calls.add("saccade"); assertEquals(SESSION,s); assertEquals(Origin.SYNTHETIC,o); assertEquals(TOKEN,t);
            if (brokenSaccade) throw new SecurityException("private upstream denial"); return summary;
        }, Clock.fixed(END.plusSeconds(60), ZoneOffset.UTC));
    }
    SessionReportV1 selected() { return selectedService().compose(DOCTOR,TOKEN,PATIENT,SESSION,null,null,null,Origin.SYNTHETIC); }
    void saccade() { detail = detail("COMPLETED",compensatorySaccadeParameters()); }
    @Test void explicitSelectionPreservesMetricsAndOmissionNeverCallsNewPort() {
        saccade(); assertSame(summary,selected().compensatorySaccade());
        assertEquals(List.of("detail","resolve","names","doctor","analytics","saccade"),calls);
        calls.clear(); assertNull(selectedService().compose(DOCTOR,TOKEN,PATIENT,SESSION).compensatorySaccade());
        assertFalse(calls.contains("saccade"));
    }
    @Test void unsupportedModeRejectedAfterClinicalAuthorizationBeforeAnyEnrichment() {
        detail = detail("COMPLETED",ComposeStandardScanningReportServiceTest.standardScanningParameters());
        var failure = assertThrows(PatientReadFailure.class,this::selected);
        assertEquals(PatientReadFailure.Kind.INVALID_REQUEST,failure.kind()); assertEquals(List.of("detail"),calls);
        calls.clear(); author = false; assertThrows(SecurityException.class,this::selected); assertEquals(List.of("detail"),calls);
    }
    @Test void selectedFailureNullAndEveryBindingMismatchFailClosed() {
        saccade(); brokenSaccade = true; assertThrows(IllegalStateException.class,this::selected);
        brokenSaccade = false;
        for (var invalid : Arrays.asList(null,summary(new UUID(0,99),CONFIG,2,Origin.SYNTHETIC),
                summary(SESSION,new UUID(0,99),2,Origin.SYNTHETIC),summary(SESSION,CONFIG,3,Origin.SYNTHETIC),
                summary(SESSION,CONFIG,2,Origin.PRODUCER_REPORTED))) {
            summary = invalid; var error = assertThrows(IllegalStateException.class,this::selected);
            assertNull(error.getCause()); assertFalse(error.toString().contains("private"));
        }
    }
    @Test void prescribedSaccadeUsesTheFrozenConfigurationAndBaselineFailuresRemainIndependent() {
        var parameters = new com.neurovision.rehab.clinical.domain.model.valueobjects.TherapeuticExerciseParameters.Prescribed(
                compensatorySaccadeParameters(),
                new com.neurovision.rehab.clinical.domain.model.valueobjects.CompletionTarget(
                        com.neurovision.rehab.clinical.domain.model.valueobjects.CompletionTarget.Criterion.ACTIVE_TIME,120));
        detail = detail("COMPLETED",parameters); assertSame(summary,selected().compensatorySaccade());
        calls.clear(); observation = null; assertThrows(IllegalStateException.class,this::selected); assertFalse(calls.contains("saccade"));
    }
    @Test void confirmedZeroRemainsASelectedSummaryWithNullMedian() {
        saccade(); summary = new Summary("compensatory-saccade-trial-v1",SESSION,CONFIG,2,Origin.SYNTHETIC,0,0,null);
        var report = selected(); assertNotNull(report.compensatorySaccade()); assertNull(report.compensatorySaccade().medianTimeToTargetMs());
        assertEquals(0,report.compensatorySaccade().reportedTrialCount());
        assertEquals("Sin evidencia analítica disponible",report.analyticsNotice());
    }
    public static com.neurovision.rehab.clinical.domain.model.valueobjects.TherapeuticExerciseParameters compensatorySaccadeParameters() {
        return new TherapeuticExerciseParameters.VisualScanning(
                TherapeuticExerciseParameters.VisualScanningMode.COMPENSATORY_SACCADE,
                TherapeuticExerciseParameters.AffectedHemifield.LEFT,500,3000,10,50,5,500,1);
    }

    @Test void medianOutsideFrozenWindowIsUnavailable() {
        saccade();
        for (int value : List.of(499,3001)) {
            summary = new Summary("compensatory-saccade-trial-v1",SESSION,CONFIG,2,Origin.SYNTHETIC,3,2,BigDecimal.valueOf(value));
            assertThrows(IllegalStateException.class,this::selected);
        }
    }

    @Test void combinedSelectionIsRejectedBeforeClinicalOrPeerReads() {
        saccade();
        var failure = assertThrows(PatientReadFailure.class, () -> selectedService().compose(DOCTOR,TOKEN,PATIENT,SESSION,
                null,SessionReportStandardScanningPort.Origin.SYNTHETIC,null,Origin.SYNTHETIC));
        assertEquals(PatientReadFailure.Kind.INVALID_REQUEST,failure.kind());
        assertTrue(calls.isEmpty());
    }
}
