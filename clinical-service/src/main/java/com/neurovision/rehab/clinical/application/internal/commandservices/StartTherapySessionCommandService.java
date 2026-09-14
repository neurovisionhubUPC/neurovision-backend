package com.neurovision.rehab.clinical.application.internal.commandservices;

import com.neurovision.rehab.clinical.application.ports.out.persistence.TherapyPlanRepository;
import com.neurovision.rehab.clinical.application.ports.out.persistence.TherapySessionRepository;
import com.neurovision.rehab.clinical.domain.model.aggregates.TherapySession;
import com.neurovision.rehab.clinical.domain.model.valueobjects.TherapySessionId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

public final class StartTherapySessionCommandService {
    public static final ZoneId QUOTA_ZONE = ZoneId.of("America/Lima");

    private final TherapyPlanRepository planRepository;
    private final TherapySessionRepository sessionRepository;
    private final Supplier<UUID> idSupplier;
    private final Clock clock;
    private final Duration leaseDuration;

    public StartTherapySessionCommandService(
            TherapyPlanRepository planRepository, TherapySessionRepository sessionRepository,
            Supplier<UUID> idSupplier, Clock clock, Duration leaseDuration) {
        this.planRepository = Objects.requireNonNull(planRepository);
        this.sessionRepository = Objects.requireNonNull(sessionRepository);
        this.idSupplier = Objects.requireNonNull(idSupplier);
        this.clock = Objects.requireNonNull(clock);
        this.leaseDuration = Objects.requireNonNull(leaseDuration);
        if (leaseDuration.isZero() || leaseDuration.isNegative()) throw new IllegalArgumentException("Lease duration must be positive");
    }

    public StartTherapySessionResult handle(StartTherapySessionCommand command) {
        Objects.requireNonNull(command);
        sessionRepository.acquirePatientStartLock(command.clinicalPatientId());
        String fingerprint = fingerprint(command);
        var previous = sessionRepository.findByPatientAndIdempotencyKey(command.clinicalPatientId(), command.idempotencyKey());
        if (previous.isPresent()) {
            if (!previous.get().requestFingerprint().equals(fingerprint)) throw TherapySessionStartConflictException.idempotencyMismatch();
            return new StartTherapySessionResult(StartTherapySessionResult.Outcome.IDEMPOTENT_REPLAY, previous.get());
        }

        var active = sessionRepository.findActiveByPatient(command.clinicalPatientId());
        if (active.isPresent()) throw TherapySessionStartConflictException.activeSession(active.get().id());

        var plan = planRepository.findById(command.therapyPlanId())
                .orElseThrow(() -> new IllegalArgumentException("Therapy plan does not exist"));
        if (!plan.clinicalPatientId().equals(command.clinicalPatientId())) throw new IllegalArgumentException("Therapy plan does not belong to patient");
        if (plan.configurationVersion() != command.requestedConfigurationVersion()) throw new IllegalStateException("Requested therapy configuration is stale");

        var now = clock.instant();
        var quotaDate = now.atZone(QUOTA_ZONE).toLocalDate();
        plan.validateAdmission(
                command.exerciseCode(),
                sessionRepository.countConsumedSlots(plan.id(), quotaDate),
                sessionRepository.countConsumedExerciseSlots(plan.id(), quotaDate, command.exerciseCode()),
                sessionRepository.sumExposureSeconds(plan.id(), quotaDate));
        var session = TherapySession.start(new TherapySessionId(idSupplier.get()), plan,
                command.exerciseCode(), quotaDate, command.idempotencyKey(), fingerprint,
                now, now.plus(leaseDuration));
        sessionRepository.save(session);
        return new StartTherapySessionResult(StartTherapySessionResult.Outcome.CREATED, session);
    }

    private static String fingerprint(StartTherapySessionCommand command) {
        String canonical = command.clinicalPatientId().value() + "\n" + command.therapyPlanId().value()
                + "\n" + command.exerciseCode().value() + "\n" + command.requestedConfigurationVersion();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
