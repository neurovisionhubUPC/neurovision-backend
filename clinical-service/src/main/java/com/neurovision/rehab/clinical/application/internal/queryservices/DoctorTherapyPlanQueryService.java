package com.neurovision.rehab.clinical.application.internal.queryservices;

import com.neurovision.rehab.clinical.application.ports.out.persistence.DoctorTherapyPlanReadRepository;
import com.neurovision.rehab.clinical.application.ports.out.persistence.DoctorTherapyPlanReadRepository.Detail;
import com.neurovision.rehab.clinical.domain.model.aggregates.TherapyPlan;
import com.neurovision.rehab.clinical.domain.model.valueobjects.*;
import java.time.Clock;
import java.util.*;
import java.util.function.Supplier;

public final class DoctorTherapyPlanQueryService {
    private final DoctorTherapyPlanReadRepository reads;
    private final Clock clock;

    public DoctorTherapyPlanQueryService(DoctorTherapyPlanReadRepository reads, Clock clock) {
        this.reads = Objects.requireNonNull(reads);
        this.clock = Objects.requireNonNull(clock);
    }

    public Page list(String doctor, UUID patient, int page, int size, String rawStatus) {
        if (page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE)
            throw new IllegalArgumentException("Invalid pagination");
        var status = rawStatus == null ? null : TherapyPlanStatus.valueOf(rawStatus);
        requireAssignment(doctor, patient);
        var at = clock.instant();
        var result = observe(() -> reads.page(patient, doctor, status, at, page * size, size));
        if (result.plans().size() > size) throw new IllegalStateException("Unbounded page");
        result.plans().forEach(plan -> requireScope(plan, doctor, patient));
        return new Page(patient, result.plans(), page, size, result.total());
    }

    public Detail detail(String doctor, UUID patient, UUID planId) {
        Objects.requireNonNull(planId);
        requireAssignment(doctor, patient);
        var at = clock.instant();
        var detail = observe(() -> reads.detail(patient, planId, doctor, at)).orElseThrow(SecurityException::new);
        requireScope(detail.plan(), doctor, patient);
        if (!detail.plan().id().value().equals(planId)) throw new SecurityException();
        var seen = new HashSet<ExerciseCode>();
        for (var config : detail.configurations()) {
            if (!config.therapyPlanId().equals(detail.plan().id())
                    || config.status() != TherapeuticConfigurationStatus.ACTIVE
                    || config.effectiveFrom().isAfter(at)
                    || !detail.plan().dailyExercisePrescriptions().containsKey(config.exercise().code())
                    || !seen.add(config.exercise().code()))
                throw new IllegalStateException("Invalid effective configuration observation");
        }
        return detail;
    }

    private void requireAssignment(String doctor, UUID patient) {
        new CognitoSubject(doctor);
        Objects.requireNonNull(patient);
        if (!observe(() -> reads.hasActiveAssignment(patient, doctor))) throw new SecurityException();
    }

    private static <T> T observe(Supplier<T> read) {
        try { return read.get(); }
        catch (RuntimeException unavailable) {
            // Persistence decoding failures are not malformed client requests.
            throw new IllegalStateException("Plan observation is unavailable", unavailable);
        }
    }

    private static void requireScope(TherapyPlan plan, String doctor, UUID patient) {
        if (!plan.doctorSubject().value().equals(doctor) || !plan.clinicalPatientId().value().equals(patient))
            throw new SecurityException();
    }

    public record Page(UUID clinicalPatientId, List<TherapyPlan> plans, int page, int size, long total) {
        public Page { plans = List.copyOf(plans); }
    }
}
