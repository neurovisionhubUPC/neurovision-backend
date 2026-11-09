package com.neurovision.rehab.clinical.application.internal.queryservices;

import com.neurovision.rehab.clinical.application.ports.out.integration.*;
import com.neurovision.rehab.clinical.application.ports.out.integration.SessionReportSustainedFixationPort.*;
import com.neurovision.rehab.clinical.support.TherapeuticConfigurationTestData;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class ComposeSustainedFixationReportServiceTest extends ComposeSessionReportServiceTest {
    Summary summary = summary(SESSION, CONFIG, 2, Origin.SYNTHETIC);
    boolean brokenFixation;
    static Summary summary(UUID session, UUID config, long version, Origin origin) {
        return new Summary("sustained-fixation-trial-v1", session, config, version, origin, 6, 3600, 3);
    }
    ComposeSessionReportService selectedService() {
        return new ComposeSessionReportService(sessions, patients, namePort, (d,t) -> {
            calls.add("doctor"); assertEquals(TOKEN,t); return doctor;
        }, (s,t) -> { calls.add("analytics"); return observation; }, (s,o,t) -> { fail("Visual Search must not be called"); return null; }, (s,o,t) -> { fail("Standard Scanning must not be called"); return null; }, (s,o,t) -> {
            calls.add("fixation"); assertEquals(SESSION,s); assertEquals(Origin.SYNTHETIC,o); assertEquals(TOKEN,t);
            if (brokenFixation) throw new SecurityException("private upstream denial"); return summary;
        }, Clock.fixed(END.plusSeconds(60), ZoneOffset.UTC));
    }
    SessionReportV1 selected() { return selectedService().compose(DOCTOR,TOKEN,PATIENT,SESSION,null,null,Origin.SYNTHETIC); }
    void fixation() { detail = detail("COMPLETED",sustainedFixationParameters()); }
    @Test void explicitSelectionPreservesMetricsAndOmissionNeverCallsNewPort() {
        fixation(); assertSame(summary,selected().sustainedFixation());
        assertEquals(List.of("detail","resolve","names","doctor","analytics","fixation"),calls);
        calls.clear(); assertNull(selectedService().compose(DOCTOR,TOKEN,PATIENT,SESSION).sustainedFixation());
        assertFalse(calls.contains("fixation"));
    }
    @Test void unsupportedModeRejectedAfterClinicalAuthorizationBeforeAnyEnrichment() {
        detail = detail("COMPLETED",ComposeStandardScanningReportServiceTest.standardScanningParameters());
        var failure = assertThrows(PatientReadFailure.class,this::selected);
        assertEquals(PatientReadFailure.Kind.INVALID_REQUEST,failure.kind()); assertEquals(List.of("detail"),calls);
        calls.clear(); author = false; assertThrows(SecurityException.class,this::selected); assertEquals(List.of("detail"),calls);
    }
    @Test void selectedFailureNullAndEveryBindingMismatchFailClosed() {
        fixation(); brokenFixation = true; assertThrows(IllegalStateException.class,this::selected);
        brokenFixation = false;
        for (var invalid : Arrays.asList(null,summary(new UUID(0,99),CONFIG,2,Origin.SYNTHETIC),
                summary(SESSION,new UUID(0,99),2,Origin.SYNTHETIC),summary(SESSION,CONFIG,3,Origin.SYNTHETIC),
                summary(SESSION,CONFIG,2,Origin.PRODUCER_REPORTED))) {
            summary = invalid; var error = assertThrows(IllegalStateException.class,this::selected);
            assertNull(error.getCause()); assertFalse(error.toString().contains("private"));
        }
    }
    @Test void prescribedFixationUsesTheFrozenConfigurationAndBaselineFailuresRemainIndependent() {
        var parameters = new com.neurovision.rehab.clinical.domain.model.valueobjects.TherapeuticExerciseParameters.Prescribed(
                sustainedFixationParameters(),
                new com.neurovision.rehab.clinical.domain.model.valueobjects.CompletionTarget(
                        com.neurovision.rehab.clinical.domain.model.valueobjects.CompletionTarget.Criterion.ACTIVE_TIME,120));
        detail = detail("COMPLETED",parameters); assertSame(summary,selected().sustainedFixation());
        calls.clear(); observation = null; assertThrows(IllegalStateException.class,this::selected); assertFalse(calls.contains("fixation"));
    }
    @Test void confirmedZeroRemainsASelectedSummaryWithZeroDuration() {
        fixation(); summary = new Summary("sustained-fixation-trial-v1",SESSION,CONFIG,2,Origin.SYNTHETIC,0,0,0);
        var report = selected(); assertNotNull(report.sustainedFixation()); assertEquals(0,report.sustainedFixation().totalInsideTargetMs());
        assertEquals(0,report.sustainedFixation().reportedTrialCount());
        assertEquals("Sin evidencia analítica disponible",report.analyticsNotice());
    }
    public static com.neurovision.rehab.clinical.domain.model.valueobjects.TherapeuticExerciseParameters sustainedFixationParameters() {
        return TherapeuticConfigurationTestData.fixationSnapshot().parameters();
    }

    @Test void combinedSelectionIsRejectedBeforeClinicalOrPeerReads() {
        fixation();
        var failure = assertThrows(PatientReadFailure.class, () -> selectedService().compose(DOCTOR,TOKEN,PATIENT,SESSION,
                null,SessionReportStandardScanningPort.Origin.SYNTHETIC,Origin.SYNTHETIC));
        assertEquals(PatientReadFailure.Kind.INVALID_REQUEST,failure.kind());
        assertTrue(calls.isEmpty());
    }
}
