package com.neurovision.rehab.clinical.application.internal.commandservices;

import com.neurovision.rehab.clinical.application.ports.out.persistence.*;
import com.neurovision.rehab.clinical.domain.model.aggregates.*;
import com.neurovision.rehab.clinical.domain.model.valueobjects.*;
import java.time.*;
import java.util.*;
import java.lang.reflect.Proxy;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DoctorSchedulingGateTest {
    private final ClinicalPatientId patient = new ClinicalPatientId(UUID.randomUUID());
    private final CognitoSubject doctor = new CognitoSubject("synthetic-doctor");
    private final TherapyPlanId planId = new TherapyPlanId(UUID.randomUUID());
    private int writes;
    private int checks;
    private boolean assigned = true;
    private final TherapyPlan plan = TherapyPlan.rehydrate(planId, patient, doctor, TherapyPlanStatus.ACTIVE,
            1, 2, 30, null, 5, 15, 50, Map.of(TherapeuticExercise.FIXATION.code(), 1));

    @SuppressWarnings("unchecked")
    private <T> T repository(Class<T> type) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            if (method.getName().equals("save") || method.getName().equals("append")) { writes++; return null; }
            if (method.getName().equals("findLatestVersion")) return OptionalLong.empty();
            if (method.getName().startsWith("findActiveByClinical")) return assigned ? Optional.of(DoctorPatientAssignment.assign(
                    new DoctorPatientAssignmentId(UUID.randomUUID()), patient, doctor, Instant.EPOCH)) : Optional.empty();
            if (method.getName().equals("findByIdForUpdate")) return Optional.of(plan);
            throw new AssertionError("Unexpected repository call");
        });
    }
    private CreateTherapyPlanCommand create() {
        return new CreateTherapyPlanCommand(patient, doctor, 2, 30, null, 5, 15, 50,
                Map.of(TherapeuticExercise.FIXATION.code(), 1));
    }
    private AppendTherapeuticConfigurationCommand append() {
        return new AppendTherapeuticConfigurationCommand(planId, patient, doctor, TherapeuticExercise.FIXATION, 1, Instant.EPOCH,
                new TherapeuticExerciseParameters.Fixation(TherapeuticExerciseParameters.FixationMode.SUSTAINED_TARGET_FIXATION,
                        TherapeuticExerciseParameters.TargetPosition.CENTER, 500, 1000, 50, 10, 800, 2));
    }
    @Test void deniesBothPublicProgrammingWritesBeforeAnyPersistence() {
        var creator = new CreateTherapyPlanCommandService(repository(DoctorPatientAssignmentRepository.class),
                repository(TherapyPlanRepository.class), UUID::randomUUID, subject -> { checks++; throw new SecurityException(); });
        var appender = new AppendTherapeuticConfigurationCommandService(repository(TherapyPlanRepository.class),
                repository(DoctorPatientAssignmentRepository.class), repository(TherapeuticExerciseConfigurationRepository.class),
                UUID::randomUUID, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), subject -> { checks++; throw new SecurityException(); });
        assertThrows(SecurityException.class, () -> creator.handle(create()));
        assertThrows(SecurityException.class, () -> appender.handle(append()));
        assertEquals(2, checks); assertEquals(0, writes);
    }
    @Test void unavailableProfileNeverWritesEitherOperation() {
        var creator = new CreateTherapyPlanCommandService(repository(DoctorPatientAssignmentRepository.class),
                repository(TherapyPlanRepository.class), UUID::randomUUID, subject -> { throw new DoctorSchedulingUnavailableException(); });
        var appender = new AppendTherapeuticConfigurationCommandService(repository(TherapyPlanRepository.class),
                repository(DoctorPatientAssignmentRepository.class), repository(TherapeuticExerciseConfigurationRepository.class),
                UUID::randomUUID, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), subject -> { throw new DoctorSchedulingUnavailableException(); });
        assertThrows(DoctorSchedulingUnavailableException.class, () -> creator.handle(create()));
        assertThrows(DoctorSchedulingUnavailableException.class, () -> appender.handle(append()));
        assertEquals(0, writes);
    }
    @Test void eligibleDoctorCanAppendConfiguration() {
        var appender = new AppendTherapeuticConfigurationCommandService(repository(TherapyPlanRepository.class),
                repository(DoctorPatientAssignmentRepository.class), repository(TherapeuticExerciseConfigurationRepository.class),
                UUID::randomUUID, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), subject -> { checks++; });
        appender.handle(append());
        assertEquals(1, writes); assertEquals(1, checks);
    }
    @Test void eligibleDoctorCanProgramButStillNeedsAnAssignment() {
        var creator = new CreateTherapyPlanCommandService(repository(DoctorPatientAssignmentRepository.class),
                repository(TherapyPlanRepository.class), UUID::randomUUID, subject -> { assertEquals(doctor, subject); checks++; });
        creator.handle(create());
        assertEquals(1, writes); assertEquals(1, checks);
        assigned = false;
        assertThrows(SecurityException.class, () -> creator.handle(create()));
        assertEquals(1, writes);
    }
}
