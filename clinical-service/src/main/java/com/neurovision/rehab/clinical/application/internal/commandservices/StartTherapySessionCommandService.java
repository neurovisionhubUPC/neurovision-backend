package com.neurovision.rehab.clinical.application.internal.commandservices;

import com.neurovision.rehab.clinical.application.ports.out.persistence.TherapyPlanRepository;
import com.neurovision.rehab.clinical.application.ports.out.persistence.TherapySessionRepository;
import com.neurovision.rehab.clinical.application.ports.out.persistence.TherapyPlanRevisionRepository;
import com.neurovision.rehab.clinical.application.ports.out.persistence.DoctorPatientAssignmentRepository;
import com.neurovision.rehab.clinical.application.ports.out.persistence.TherapeuticExerciseConfigurationRepository;
import com.neurovision.rehab.clinical.domain.model.aggregates.TherapyPlanRevision;
import com.neurovision.rehab.clinical.domain.model.aggregates.TherapySession;
import com.neurovision.rehab.clinical.domain.model.valueobjects.TherapySessionId;
import com.neurovision.rehab.clinical.domain.model.valueobjects.TherapySessionStatus;
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
                .orElseThrow(() -> new SecurityException("Therapy operation is not permitted"));
        if (!basePlan.clinicalPatientId().equals(command.clinicalPatientId())) {
            throw new SecurityException("Therapy operation is not permitted");
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

        var now = clock.instant();
        var active = sessionRepository.findActiveByPatient(command.clinicalPatientId());
        boolean expiredLease = false;
        if (active.isPresent()) {
            // A concurrent transition may have completed after the active lookup.
            var current = sessionRepository.findByIdForUpdate(active.get().id()).orElseThrow();
            if (current.status() == TherapySessionStatus.ACTIVE) {
                if (now.isBefore(current.leaseExpiresAt().orElseThrow())) {
                    throw TherapySessionStartConflictException.activeSession(current.id());
                }
                current.expireLease(now);
                sessionRepository.save(current);
                expiredLease = true;
            }
        }

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
        if (configuration.parameters() instanceof com.neurovision.rehab.clinical.domain.model.valueobjects.TherapeuticExerciseParameters.Prescribed prescribed
                && prescribed.completionTarget().criterion() == com.neurovision.rehab.clinical.domain.model.valueobjects.CompletionTarget.Criterion.ACTIVE_TIME
                && prescribed.completionTarget().target() > plan.maximumSessionDurationMinutes() * 60L) {
            throw new IllegalStateException("Completion target exceeds session safety limit");
        }
        long consumedSlots = sessionRepository.countConsumedSlots(plan.id(), quotaDate);
        long consumedExerciseSlots = sessionRepository.countConsumedExerciseSlots(
                plan.id(), quotaDate, command.exerciseCode());
        long exposureSeconds = sessionRepository.sumExposureSeconds(plan.id(), quotaDate);
        try {
            plan.validateAdmission(command.exerciseCode(), consumedSlots, consumedExerciseSlots, exposureSeconds);
        } catch (IllegalStateException denied) {
            if (!expiredLease) throw denied;
            throw TherapySessionStartConflictException.admissionDenied();
        }
        var session = TherapySession.start(new TherapySessionId(idSupplier.get()), plan,
                command.exerciseCode(), quotaDate, TherapeuticConfigurationSnapshot.from(configuration),
                command.idempotencyKey(), fingerprint,
                now, now.plus(leaseDuration));
        sessionRepository.save(session);
        return new StartTherapySessionResult(StartTherapySessionResult.Outcome.CREATED, session);
    }

}
