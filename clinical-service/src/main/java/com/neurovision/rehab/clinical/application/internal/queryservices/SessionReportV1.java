package com.neurovision.rehab.clinical.application.internal.queryservices;

import com.neurovision.rehab.clinical.application.ports.out.integration.AssignedPatientNamesPort.Name;
import com.neurovision.rehab.clinical.application.ports.out.integration.DoctorReportIdentityPort.Identity;
import com.neurovision.rehab.clinical.application.ports.out.integration.SessionReportAnalyticsPort.Observation;
import com.neurovision.rehab.clinical.application.ports.out.integration.SessionReportAnalyticsPort.ConfirmedNoEvidence;
import com.neurovision.rehab.clinical.application.ports.out.persistence.DoctorTherapySessionReadRepository.*;
import com.neurovision.rehab.clinical.domain.model.valueobjects.*;
import com.neurovision.rehab.clinical.application.ports.out.integration.SessionReportVisualSearchPort.Summary;
import com.neurovision.rehab.clinical.application.ports.out.integration.SessionReportSustainedFixationPort;
import com.neurovision.rehab.clinical.application.ports.out.integration.SessionReportCompensatorySaccadePort;
import com.neurovision.rehab.clinical.application.ports.out.integration.SessionReportSystematicExplorationPort;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** Ephemeral application read model, not a persisted snapshot or transport DTO.
 * Identity fields are current at issuedAt; configuration and execution are session-frozen values.
 * Technical progress is not clinical efficacy or a physiological fixation/trial outcome.
 */
public record SessionReportV1(UUID clinicalPatientId, Identity doctor, Name patient,
        Execution execution, FrozenConfiguration configuration, SessionLimits sessionLimits,
        TechnicalProgress technicalProgress, Observation analytics, Instant issuedAt, Summary visualSearch,
        com.neurovision.rehab.clinical.application.ports.out.integration.SessionReportStandardScanningPort.Summary standardScanning,
        SessionReportSustainedFixationPort.Summary sustainedFixation,
        SessionReportCompensatorySaccadePort.Summary compensatorySaccade,
        SessionReportSystematicExplorationPort.Summary systematicExploration) {
    public SessionReportV1(UUID clinicalPatientId, Identity doctor, Name patient, Execution execution,
            FrozenConfiguration configuration, SessionLimits sessionLimits, TechnicalProgress technicalProgress,
            Observation analytics, Instant issuedAt, Summary visualSearch,
            com.neurovision.rehab.clinical.application.ports.out.integration.SessionReportStandardScanningPort.Summary standardScanning,
            SessionReportSustainedFixationPort.Summary sustainedFixation,
            SessionReportCompensatorySaccadePort.Summary compensatorySaccade) {
        this(clinicalPatientId,doctor,patient,execution,configuration,sessionLimits,technicalProgress,analytics,issuedAt,
                visualSearch,standardScanning,sustainedFixation,compensatorySaccade,null);
    }
    public SessionReportV1(UUID clinicalPatientId, Identity doctor, Name patient, Execution execution,
            FrozenConfiguration configuration, SessionLimits sessionLimits, TechnicalProgress technicalProgress,
            Observation analytics, Instant issuedAt, Summary visualSearch,
            com.neurovision.rehab.clinical.application.ports.out.integration.SessionReportStandardScanningPort.Summary standardScanning,
            SessionReportSustainedFixationPort.Summary sustainedFixation) {
        this(clinicalPatientId,doctor,patient,execution,configuration,sessionLimits,technicalProgress,analytics,issuedAt,
                visualSearch,standardScanning,sustainedFixation,null);
    }
    public SessionReportV1(UUID clinicalPatientId, Identity doctor, Name patient, Execution execution,
            FrozenConfiguration configuration, SessionLimits sessionLimits, TechnicalProgress technicalProgress,
            Observation analytics, Instant issuedAt, Summary visualSearch,
            com.neurovision.rehab.clinical.application.ports.out.integration.SessionReportStandardScanningPort.Summary standardScanning) {
        this(clinicalPatientId,doctor,patient,execution,configuration,sessionLimits,technicalProgress,analytics,issuedAt,
                visualSearch,standardScanning,null);
    }
    public SessionReportV1(UUID clinicalPatientId, Identity doctor, Name patient, Execution execution,
            FrozenConfiguration configuration, SessionLimits sessionLimits, TechnicalProgress technicalProgress,
            Observation analytics, Instant issuedAt, Summary visualSearch) {
        this(clinicalPatientId,doctor,patient,execution,configuration,sessionLimits,technicalProgress,analytics,issuedAt,visualSearch,null);
    }
    /** Existing callers retain the exact omitted-selection behavior. */
    public SessionReportV1(UUID clinicalPatientId, Identity doctor, Name patient, Execution execution,
            FrozenConfiguration configuration, SessionLimits sessionLimits, TechnicalProgress technicalProgress,
            Observation analytics, Instant issuedAt) {
        this(clinicalPatientId,doctor,patient,execution,configuration,sessionLimits,technicalProgress,analytics,issuedAt,null);
    }
    public SessionReportV1 {
        Objects.requireNonNull(clinicalPatientId); Objects.requireNonNull(doctor); Objects.requireNonNull(patient);
        Objects.requireNonNull(execution); Objects.requireNonNull(configuration); Objects.requireNonNull(sessionLimits);
        Objects.requireNonNull(technicalProgress); Objects.requireNonNull(analytics); Objects.requireNonNull(issuedAt);
        require(execution.therapySessionId().equals(analytics.therapySessionId()));
        if (visualSearch != null) {
            require(supportsVisualSearch(configuration.parameters())
                    && execution.therapySessionId().equals(visualSearch.therapySessionId())
                    && configuration.configurationId().equals(visualSearch.configurationId())
                    && configuration.configurationVersion() == visualSearch.configurationVersion());
        }
        require(visualSearch == null || standardScanning == null);
        if (standardScanning != null) {
            require(supportsStandardScanning(configuration.parameters())
                    && execution.therapySessionId().equals(standardScanning.therapySessionId())
                    && configuration.configurationId().equals(standardScanning.configurationId())
                    && configuration.configurationVersion() == standardScanning.configurationVersion());
        }
        if (sustainedFixation != null) {
            var fixation = sustainedFixationParameters(configuration.parameters());
            require(visualSearch == null && standardScanning == null && fixation != null
                    && execution.therapySessionId().equals(sustainedFixation.therapySessionId())
                    && configuration.configurationId().equals(sustainedFixation.configurationId())
                    && configuration.configurationVersion() == sustainedFixation.configurationVersion()
                    && sustainedFixation.totalInsideTargetMs() >= sustainedFixation.completedTargetCount() * fixation.minimumFixationMs()
                    && sustainedFixation.totalInsideTargetMs() <= sustainedFixation.reportedTrialCount() * fixation.maximumFixationMs());
        }
        if (compensatorySaccade != null) {
            var policy = compensatorySaccadeParameters(configuration.parameters());
            var median = compensatorySaccade.medianTimeToTargetMs();
            require(visualSearch == null && standardScanning == null && sustainedFixation == null && policy != null
                    && execution.therapySessionId().equals(compensatorySaccade.therapySessionId())
                    && configuration.configurationId().equals(compensatorySaccade.configurationId())
                    && configuration.configurationVersion() == compensatorySaccade.configurationVersion()
                    && (median == null || (median.compareTo(BigDecimal.valueOf(policy.minimumFixationMs())) >= 0
                    && median.compareTo(BigDecimal.valueOf(policy.maximumStimulusDurationMs())) <= 0)));
        }
        if (systematicExploration != null) {
            var policy = systematicExplorationParameters(configuration.parameters());
            require(visualSearch == null && standardScanning == null && sustainedFixation == null && compensatorySaccade == null
                    && policy != null && execution.therapySessionId().equals(systematicExploration.therapySessionId())
                    && configuration.configurationId().equals(systematicExploration.configurationId())
                    && configuration.configurationVersion() == systematicExploration.configurationVersion());
            for (var row : systematicExploration.traversals())
                row.validateWindow(policy.minimumFixationMs(),policy.maximumStimulusDurationMs());
        }
        // Reuse the existing frozen-configuration/progress consistency rule, including legacy null semantics.
        new Detail(clinicalPatientId,new Session(execution.therapySessionId(),execution.therapyPlanId(),
                configuration.parameters().exercise().code().value(),execution.status(),execution.startedAt(),execution.endedAt(),
                execution.activeDurationSeconds(),execution.quotaDate(),configuration.configurationId(),
                configuration.configurationVersion(),configuration.parameterSchemaVersion()),
                configuration.parameters(),sessionLimits,technicalProgress);
    }
    static boolean supportsVisualSearch(TherapeuticExerciseParameters parameters) {
        if (parameters instanceof TherapeuticExerciseParameters.Prescribed p) parameters = p.parameters();
        return parameters instanceof TherapeuticExerciseParameters.VisualSearch search
                && "TARGET_SEARCH_WITH_DISTRACTORS".equals(search.mode().name());
    }
    static boolean supportsStandardScanning(TherapeuticExerciseParameters parameters) {
        if (parameters instanceof TherapeuticExerciseParameters.Prescribed p) parameters = p.parameters();
        return parameters instanceof TherapeuticExerciseParameters.VisualScanning scanning
                && "STANDARD_SCANNING".equals(scanning.mode().name());
    }
    static TherapeuticExerciseParameters.Fixation sustainedFixationParameters(TherapeuticExerciseParameters parameters) {
        if (parameters instanceof TherapeuticExerciseParameters.Prescribed p) parameters = p.parameters();
        return parameters instanceof TherapeuticExerciseParameters.Fixation fixation
                && fixation.mode() == TherapeuticExerciseParameters.FixationMode.SUSTAINED_TARGET_FIXATION ? fixation : null;
    }
    static TherapeuticExerciseParameters.VisualScanning compensatorySaccadeParameters(TherapeuticExerciseParameters parameters) {
        if (parameters instanceof TherapeuticExerciseParameters.Prescribed p) parameters = p.parameters();
        return parameters instanceof TherapeuticExerciseParameters.VisualScanning scanning
                && scanning.mode() == TherapeuticExerciseParameters.VisualScanningMode.COMPENSATORY_SACCADE ? scanning : null;
    }
    static TherapeuticExerciseParameters.VisualScanning systematicExplorationParameters(TherapeuticExerciseParameters parameters) {
        if (parameters instanceof TherapeuticExerciseParameters.Prescribed p) parameters = p.parameters();
        return parameters instanceof TherapeuticExerciseParameters.VisualScanning scanning
                && scanning.mode() == TherapeuticExerciseParameters.VisualScanningMode.SYSTEMATIC_EXPLORATION ? scanning : null;
    }
    public String mode() {
        var parameters = configuration.parameters();
        if (parameters instanceof TherapeuticExerciseParameters.Prescribed prescribed) parameters = prescribed.parameters();
        if (parameters instanceof TherapeuticExerciseParameters.VisualScanning scanning) return scanning.mode().name();
        if (parameters instanceof TherapeuticExerciseParameters.VisualSearch search) return search.mode().name();
        return ((TherapeuticExerciseParameters.Fixation)parameters).mode().name();
    }
    /** Null when observed evidence exists; never converts a dependency error to a notice. */
    public String analyticsNotice() {
        return analytics instanceof ConfirmedNoEvidence ? "Sin evidencia analítica disponible" : null;
    }
    @Override public String toString() { return "SessionReportV1[redacted]"; }

    public record Execution(UUID therapySessionId, UUID therapyPlanId, String status, Instant startedAt, Instant endedAt,
            long activeDurationSeconds, LocalDate quotaDate) {
        public Execution {
            Objects.requireNonNull(therapySessionId); Objects.requireNonNull(therapyPlanId);
            Objects.requireNonNull(startedAt); Objects.requireNonNull(endedAt); Objects.requireNonNull(quotaDate);
            require("COMPLETED".equals(status) && !endedAt.isBefore(startedAt) && activeDurationSeconds >= 0);
        }
        @Override public String toString() { return "SessionExecution[redacted]"; }
    }
    public record FrozenConfiguration(UUID configurationId, long configurationVersion,
            int parameterSchemaVersion, TherapeuticExerciseParameters parameters) {
        public FrozenConfiguration {
            Objects.requireNonNull(configurationId); Objects.requireNonNull(parameters);
            require(configurationVersion > 0 && parameterSchemaVersion == parameters.schemaVersion());
        }
        @Override public String toString() { return "FrozenConfiguration[redacted]"; }
    }
    static Execution execution(Detail detail) {
        var session = detail.session();
        return new Execution(session.therapySessionId(),session.therapyPlanId(),session.status(),session.startedAt(),
                session.endedAt(),session.activeDurationSeconds(),session.quotaDate());
    }
    static FrozenConfiguration configuration(Detail detail) {
        var session = detail.session();
        return new FrozenConfiguration(session.configurationId(),session.configurationVersion(),
                session.parameterSchemaVersion(),detail.configurationParameters());
    }
    private static void require(boolean condition) {
        if (!condition) throw new IllegalArgumentException("Invalid session report values");
    }
}
