package com.neurovision.rehab.clinical.application.internal.commandservices;

import com.neurovision.rehab.clinical.application.ports.out.persistence.TherapyPlanRepository;
import com.neurovision.rehab.clinical.application.ports.out.persistence.TherapySessionRepository;
import com.neurovision.rehab.clinical.application.ports.out.persistence.TherapyPlanRevisionRepository;
import com.neurovision.rehab.clinical.application.ports.out.persistence.DoctorPatientAssignmentRepository;
import com.neurovision.rehab.clinical.application.ports.out.persistence.TherapeuticExerciseConfigurationRepository;
import com.neurovision.rehab.clinical.domain.model.aggregates.TherapyPlanRevision;
import com.neurovision.rehab.clinical.domain.model.aggregates.TherapySession;
import com.neurovision.rehab.clinical.domain.model.valueobjects.TherapySessionId;
import com.neurovision.rehab.clinical.domain.model.valueobjects.TherapeuticConfigurationSnapshot;
import com.neurovision.rehab.clinical.domain.model.valueobjects.TherapeuticConfigurationStatus;
import com.neurovision.rehab.clinical.domain.model.valueobjects.TherapeuticExercise;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

public final class StartTherapySessionCommandService {
    public static final ZoneId QUOTA_ZONE = ZoneId.of("America/Lima");

    private final TherapyPlanRepository planRepository;
    private final TherapySessionRepository sessionRepository;
    private final TherapyPlanRevisionRepository revisionRepository;
    private final DoctorPatientAssignmentRepository assignmentRepository;
    private final TherapeuticExerciseConfigurationRepository configurationRepository;
    private final Supplier<UUID> idSupplier;
    private final Clock clock;
    private final Duration leaseDuration;

    public StartTherapySessionCommandService(
            TherapyPlanRepository planRepository, TherapySessionRepository sessionRepository,
            TherapyPlanRevisionRepository revisionRepository,
            DoctorPatientAssignmentRepository assignmentRepository,
            TherapeuticExerciseConfigurationRepository configurationRepository,
            Supplier<UUID> idSupplier, Clock clock, Duration leaseDuration) {
        this.planRepository = Objects.requireNonNull(planRepository);
        this.sessionRepository = Objects.requireNonNull(sessionRepository);
        this.revisionRepository = Objects.requireNonNull(revisionRepository);
        this.assignmentRepository = Objects.requireNonNull(assignmentRepository);
        this.configurationRepository = Objects.requireNonNull(configurationRepository);
        this.idSupplier = Objects.requireNonNull(idSupplier);
        this.clock = Objects.requireNonNull(clock);
        this.leaseDuration = Objects.requireNonNull(leaseDuration);
        if (leaseDuration.isZero() || leaseDuration.isNegative()) throw new IllegalArgumentException("Lease duration must be positive");
    }

    public StartTherapySessionResult handle(StartTherapySessionCommand command) {
        Objects.requireNonNull(command);
        // Plan-first locking serializes admission against immediate plan safety revisions.
        var basePlan = planRepository.findByIdForUpdate(command.therapyPlanId())
                .orElseThrow(() -> new IllegalArgumentException("Therapy plan does not exist"));
        if (!basePlan.clinicalPatientId().equals(command.clinicalPatientId())) {
            throw new IllegalArgumentException("Therapy plan does not belong to patient");
        }
        String fingerprint = StartTherapySessionRequestFingerprint.compute(command);
        var previous = sessionRepository.findByPatientAndIdempotencyKey(command.clinicalPatientId(), command.idempotencyKey());
        if (previous.isPresent()) {
            if (!previous.get().requestFingerprint().equals(fingerprint)) throw TherapySessionStartConflictException.idempotencyMismatch();
            return new StartTherapySessionResult(StartTherapySessionResult.Outcome.IDEMPOTENT_REPLAY, previous.get());
        }

        // New admissions use the repository-wide plan -> assignment -> patient lock order.
        assignmentRepository.findActiveByClinicalPatientIdAndDoctorSubjectForUpdate(
                        command.clinicalPatientId(), basePlan.doctorSubject())
                .orElseThrow(() -> new SecurityException("Active doctor-patient assignment is required"));
        sessionRepository.acquirePatientStartLock(command.clinicalPatientId());
        previous = sessionRepository.findByPatientAndIdempotencyKey(
                command.clinicalPatientId(), command.idempotencyKey());
        if (previous.isPresent()) {
            if (!previous.get().requestFingerprint().equals(fingerprint)) {
                throw TherapySessionStartConflictException.idempotencyMismatch();
            }
            return new StartTherapySessionResult(
                    StartTherapySessionResult.Outcome.IDEMPOTENT_REPLAY, previous.get());
        }

        var active = sessionRepository.findActiveByPatient(command.clinicalPatientId());
        if (active.isPresent()) throw TherapySessionStartConflictException.activeSession(active.get().id());

        var now = clock.instant();
        var plan = TherapyPlanRevision.applyEligible(basePlan,
                revisionRepository.findEffective(basePlan.id(), now), now);
        var therapeuticExercise = TherapeuticExercise.from(command.exerciseCode());
        var configuration = configurationRepository.findEffective(plan.id(), therapeuticExercise, now)
                .filter(value -> value.therapyPlanId().equals(plan.id()))
                .filter(value -> value.exercise() == therapeuticExercise)
                .filter(value -> value.status() == TherapeuticConfigurationStatus.ACTIVE)
                .filter(value -> !value.effectiveFrom().isAfter(now))
                .orElseThrow(TherapySessionConfigurationUnavailableException::new);

        var quotaDate = now.atZone(QUOTA_ZONE).toLocalDate();
        plan.validateAdmission(
                command.exerciseCode(),
                sessionRepository.countConsumedSlots(plan.id(), quotaDate),
                sessionRepository.countConsumedExerciseSlots(plan.id(), quotaDate, command.exerciseCode()),
                sessionRepository.sumExposureSeconds(plan.id(), quotaDate));
        var session = TherapySession.start(new TherapySessionId(idSupplier.get()), plan,
                command.exerciseCode(), quotaDate, TherapeuticConfigurationSnapshot.from(configuration),
                command.idempotencyKey(), fingerprint,
                now, now.plus(leaseDuration));
        sessionRepository.save(session);
        return new StartTherapySessionResult(StartTherapySessionResult.Outcome.CREATED, session);
    }

}
