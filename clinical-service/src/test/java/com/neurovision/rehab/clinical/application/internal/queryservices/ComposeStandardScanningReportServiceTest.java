package com.neurovision.rehab.clinical.application.internal.queryservices;

import com.neurovision.rehab.clinical.application.ports.out.integration.*;
import com.neurovision.rehab.clinical.application.ports.out.integration.SessionReportStandardScanningPort.*;
import com.neurovision.rehab.clinical.support.TherapeuticConfigurationTestData;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class ComposeStandardScanningReportServiceTest extends ComposeSessionReportServiceTest {
    Summary summary = summary(SESSION, CONFIG, 2, Origin.SYNTHETIC);
    boolean brokenVisual;
    static Summary summary(UUID session, UUID config, long version, Origin origin) {
        return new Summary("standard-scanning-trial-v1", session, config, version, origin, 6, 3, 2, 1, 3, new BigDecimal("12.5"));
    }
    ComposeSessionReportService selectedService() {
        return new ComposeSessionReportService(sessions, patients, namePort, (d,t) -> {
            calls.add("doctor"); assertEquals(TOKEN,t); return doctor;
        }, (s,t) -> { calls.add("analytics"); return observation; }, (s,o,t) -> { fail("Visual Search must not be called"); return null; }, (s,o,t) -> {
            calls.add("visual"); assertEquals(SESSION,s); assertEquals(Origin.SYNTHETIC,o); assertEquals(TOKEN,t);
            if (brokenVisual) throw new SecurityException("private upstream denial"); return summary;
        }, Clock.fixed(END.plusSeconds(60), ZoneOffset.UTC));
    }
    SessionReportV1 selected() { return selectedService().compose(DOCTOR,TOKEN,PATIENT,SESSION,null,Origin.SYNTHETIC); }
    void search() { detail = detail("COMPLETED",standardScanningParameters()); }
    @Test void explicitSelectionPreservesMetricsAndOmissionNeverCallsNewPort() {
        search(); assertSame(summary,selected().standardScanning());
        assertEquals(List.of("detail","resolve","names","doctor","analytics","visual"),calls);
        calls.clear(); assertNull(selectedService().compose(DOCTOR,TOKEN,PATIENT,SESSION).standardScanning());
        assertFalse(calls.contains("visual"));
    }
    @Test void unsupportedModeRejectedAfterClinicalAuthorizationBeforeAnyEnrichment() {
        var failure = assertThrows(PatientReadFailure.class,this::selected);
        assertEquals(PatientReadFailure.Kind.INVALID_REQUEST,failure.kind()); assertEquals(List.of("detail"),calls);
        calls.clear(); author = false; assertThrows(SecurityException.class,this::selected); assertEquals(List.of("detail"),calls);
    }
    @Test void selectedFailureNullAndEveryBindingMismatchFailClosed() {
        search(); brokenVisual = true; assertThrows(IllegalStateException.class,this::selected);
        brokenVisual = false;
        for (var invalid : Arrays.asList(null,summary(new UUID(0,99),CONFIG,2,Origin.SYNTHETIC),
                summary(SESSION,new UUID(0,99),2,Origin.SYNTHETIC),summary(SESSION,CONFIG,3,Origin.SYNTHETIC),
                summary(SESSION,CONFIG,2,Origin.PRODUCER_REPORTED))) {
            summary = invalid; var error = assertThrows(IllegalStateException.class,this::selected);
            assertNull(error.getCause()); assertFalse(error.toString().contains("private"));
        }
    }
    @Test void prescribedSearchUsesTheFrozenConfigurationAndBaselineFailuresRemainIndependent() {
        var parameters = new com.neurovision.rehab.clinical.domain.model.valueobjects.TherapeuticExerciseParameters.Prescribed(
                standardScanningParameters(),
                new com.neurovision.rehab.clinical.domain.model.valueobjects.CompletionTarget(
                        com.neurovision.rehab.clinical.domain.model.valueobjects.CompletionTarget.Criterion.ACTIVE_TIME,120));
        detail = detail("COMPLETED",parameters); assertSame(summary,selected().standardScanning());
        calls.clear(); observation = null; assertThrows(IllegalStateException.class,this::selected); assertFalse(calls.contains("visual"));
    }
    @Test void confirmedZeroRemainsASelectedSummaryWithNullMedian() {
        search(); summary = new Summary("standard-scanning-trial-v1",SESSION,CONFIG,2,Origin.SYNTHETIC,0,0,0,0,0,null);
        var report = selected(); assertNotNull(report.standardScanning()); assertNull(report.standardScanning().medianLocalizationDurationMs());
        assertEquals(0,report.standardScanning().reportedTrialCount());
        assertEquals("Sin evidencia analítica disponible",report.analyticsNotice());
    }
    public static com.neurovision.rehab.clinical.domain.model.valueobjects.TherapeuticExerciseParameters standardScanningParameters() {
        return new com.neurovision.rehab.clinical.domain.model.valueobjects.TherapeuticExerciseParameters.VisualScanning(
                com.neurovision.rehab.clinical.domain.model.valueobjects.TherapeuticExerciseParameters.VisualScanningMode.STANDARD_SCANNING,
                com.neurovision.rehab.clinical.domain.model.valueobjects.TherapeuticExerciseParameters.AffectedHemifield.LEFT,500,3000,10,50,5,500,1);
    }
}
