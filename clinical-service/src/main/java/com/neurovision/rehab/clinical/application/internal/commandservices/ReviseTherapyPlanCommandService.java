package com.neurovision.rehab.clinical.application.internal.commandservices;

import com.neurovision.rehab.clinical.application.ports.out.persistence.*;
import com.neurovision.rehab.clinical.domain.model.aggregates.*;
import com.neurovision.rehab.clinical.domain.model.valueobjects.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;

public class ReviseTherapyPlanCommandService {
    public static final ZoneId QUOTA_ZONE = ZoneId.of("America/Lima");

    private final TherapyPlanRepository plans;
    private final TherapyPlanRevisionRepository revisions;
    private final DoctorPatientAssignmentRepository assignments;
    private final TherapySessionRepository sessions;
    private final Supplier<UUID> identifiers;
    private final Clock clock;

    public ReviseTherapyPlanCommandService(
            TherapyPlanRepository plans, TherapyPlanRevisionRepository revisions,
            DoctorPatientAssignmentRepository assignments, TherapySessionRepository sessions,
            Supplier<UUID> identifiers, Clock clock) {
        this.plans = Objects.requireNonNull(plans);
        this.revisions = Objects.requireNonNull(revisions);
        this.assignments = Objects.requireNonNull(assignments);
        this.sessions = Objects.requireNonNull(sessions);
        this.identifiers = Objects.requireNonNull(identifiers);
        this.clock = Objects.requireNonNull(clock);
    }

    public ReviseTherapyPlanResult handle(ReviseTherapyPlanCommand command) {
        Objects.requireNonNull(command);

        // Shared lock discipline for existing plans: plan -> assignment -> active session.
        var base = plans.findByIdForUpdate(command.therapyPlanId())
                .orElseThrow(() -> new IllegalArgumentException("Therapy plan does not exist"));
        if (!base.clinicalPatientId().equals(command.clinicalPatientId())
                || !base.doctorSubject().equals(command.doctorSubject())) {
            throw new SecurityException("Doctor is not authorized for this therapy plan");
        }
        assignments.findActiveByClinicalPatientIdAndDoctorSubjectForUpdate(
                        command.clinicalPatientId(), command.doctorSubject())
                .orElseThrow(() -> new SecurityException("Active doctor-patient assignment is required"));

        // Time is authoritative only after serialization. A command may wait on the plan lock
        // across a quota-day boundary or behind a terminal plan revision.
        Instant now = clock.instant();

        String fingerprint = fingerprint(command);
        var replay = revisions.findByCommandId(command.therapyPlanId(), command.commandId());
        if (!replay.isEmpty()) {
            if (replay.stream().anyMatch(revision -> !revision.commandFingerprint().equals(fingerprint))) {
                throw new TherapyPlanRevisionConflictException("Command identity was reused with different plan changes");
            }
            return new ReviseTherapyPlanResult(true, replay);
        }

        var current = TherapyPlanRevision.applyEligible(
                base, revisions.findEffective(base.id(), now), now);
        if (current.status() == TherapyPlanStatus.CANCELLED) {
            throw new IllegalStateException("Cancelled therapy plans are terminal");
        }

        Instant nextQuotaDay = nextQuotaDay(now);
        var target = TherapyPlanRevision.applyEligible(
                base, revisions.findEffective(base.id(), nextQuotaDay), nextQuotaDay);
        var split = split(command, current, target, fingerprint, now, nextQuotaDay,
                revisions.findLatestSequence(base.id()));
        TherapyPlan prospective = current;
        for (var revision : split) prospective = revision.applyTo(prospective);

        boolean safetyInterruption = split.stream().anyMatch(revision ->
                revision.kind() == TherapyPlanRevisionKind.IMMEDIATE
                        && (revision.status().isPresent()
                                || isHardSafetyReduction(revision, current)));
        if (safetyInterruption) {
            sessions.findActiveByPlanForUpdate(base.id()).ifPresent(session -> {
                session.cancel(command.clinicalReason(), now);
                sessions.save(session);
            });
        }

        revisions.appendAll(split);
        return new ReviseTherapyPlanResult(false, split);
    }

    private List<TherapyPlanRevision> split(
            ReviseTherapyPlanCommand command, TherapyPlan current, TherapyPlan target,
            String fingerprint, Instant now, Instant nextQuotaDay, long previousSequence) {
        Integer immediateSessions = lower(command.dailySessionLimit(), current.dailySessionLimit());
        Integer deferredSessions = immediateSessions == null
                ? differing(command.dailySessionLimit(), target.dailySessionLimit()) : null;
        Integer immediateTarget = lower(command.dailyTargetDurationMinutes(), current.dailyTargetDurationMinutes());
        Integer deferredTarget = immediateTarget == null
                ? differing(command.dailyTargetDurationMinutes(), target.dailyTargetDurationMinutes()) : null;
        Integer currentHard = current.dailyHardSafetyLimitMinutes().isPresent()
                ? current.dailyHardSafetyLimitMinutes().getAsInt() : null;
        Integer targetHard = target.dailyHardSafetyLimitMinutes().isPresent()
                ? target.dailyHardSafetyLimitMinutes().getAsInt() : null;
        Integer immediateHard = command.dailyHardSafetyLimitMinutes() != null
                        && (currentHard == null || command.dailyHardSafetyLimitMinutes() < currentHard)
                ? command.dailyHardSafetyLimitMinutes() : null;
        Integer deferredHard = immediateHard == null
                ? differing(command.dailyHardSafetyLimitMinutes(), targetHard) : null;
        Map<ExerciseCode, Integer> deferredDistribution = command.dailyExercisePrescriptions() != null
                        && !command.dailyExercisePrescriptions().equals(target.dailyExercisePrescriptions())
                ? command.dailyExercisePrescriptions() : null;

        boolean hasImmediate = command.status() != null || immediateSessions != null
                || immediateTarget != null || immediateHard != null;
        boolean hasDeferred = deferredSessions != null || deferredTarget != null
                || deferredHard != null || deferredDistribution != null;
        if (!hasImmediate && !hasDeferred) throw new IllegalArgumentException("Plan revision does not change effective values");

        var result = new ArrayList<TherapyPlanRevision>(2);
        long sequence = previousSequence;
        if (hasImmediate) {
            result.add(TherapyPlanRevision.create(new TherapyPlanRevisionId(identifiers.get()), current.id(),
                    ++sequence, command.commandId(), fingerprint, TherapyPlanRevisionKind.IMMEDIATE,
                    now, now, command.doctorSubject(), command.status(), immediateSessions,
                    immediateTarget, immediateHard, null, command.clinicalReason()));
        }
        if (hasDeferred) {
            result.add(TherapyPlanRevision.create(new TherapyPlanRevisionId(identifiers.get()), current.id(),
                    ++sequence, command.commandId(), fingerprint, TherapyPlanRevisionKind.DEFERRED,
                    nextQuotaDay, now, command.doctorSubject(), null, deferredSessions,
                    deferredTarget, deferredHard, deferredDistribution, null));
        }
        return List.copyOf(result);
    }

    private static Instant nextQuotaDay(Instant now) {
        return now.atZone(QUOTA_ZONE).toLocalDate().plusDays(1)
                .atStartOfDay(QUOTA_ZONE).toInstant();
    }

    private static boolean isHardSafetyReduction(TherapyPlanRevision revision, TherapyPlan current) {
        if (revision.dailyHardSafetyLimitMinutes().isEmpty()) return false;
        return current.dailyHardSafetyLimitMinutes().isEmpty()
                || revision.dailyHardSafetyLimitMinutes().getAsInt()
                        < current.dailyHardSafetyLimitMinutes().getAsInt();
    }

    private static Integer lower(Integer requested, int current) {
        return requested != null && requested < current ? requested : null;
    }

    private static Integer differing(Integer requested, Integer target) {
        return requested != null && !requested.equals(target) ? requested : null;
    }

    private static String fingerprint(ReviseTherapyPlanCommand command) {
        var canonical = new StringBuilder()
                .append(command.therapyPlanId().value()).append('\n')
                .append(command.clinicalPatientId().value()).append('\n')
                .append(command.doctorSubject().value()).append('\n')
                .append(command.dailySessionLimit()).append('\n')
                .append(command.dailyTargetDurationMinutes()).append('\n')
                .append(command.dailyHardSafetyLimitMinutes()).append('\n')
                .append(command.status()).append('\n')
                .append(command.clinicalReason() == null ? null : command.clinicalReason().trim()).append('\n');
        if (command.dailyExercisePrescriptions() != null) {
            new TreeMap<>(command.dailyExercisePrescriptions()).forEach((code, quantity) ->
                    canonical.append(code.value()).append('=').append(quantity).append(';'));
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
