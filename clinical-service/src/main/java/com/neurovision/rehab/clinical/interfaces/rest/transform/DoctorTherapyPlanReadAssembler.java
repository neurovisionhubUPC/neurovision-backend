package com.neurovision.rehab.clinical.interfaces.rest.transform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neurovision.rehab.clinical.application.internal.queryservices.DoctorTherapyPlanQueryService;
import com.neurovision.rehab.clinical.application.ports.out.persistence.DoctorTherapyPlanReadRepository;
import com.neurovision.rehab.clinical.interfaces.rest.resource.DoctorTherapyPlanReadResponse.*;

public final class DoctorTherapyPlanReadAssembler {
    private DoctorTherapyPlanReadAssembler() {}
    public static Page page(DoctorTherapyPlanQueryService.Page page) {
        return new Page(page.clinicalPatientId(), page.plans().stream()
                .map(p -> new Summary(p.id().value(), p.status().name(), p.configurationVersion())).toList(),
                page.page(), page.size(), page.total());
    }
    public static Detail detail(DoctorTherapyPlanReadRepository.Detail detail, ObjectMapper mapper) {
        var p = TherapyPlanRestAssembler.toResponse(detail.plan());
        var configs = detail.configurations().stream().map(c -> new Configuration(c.id().value(),
                c.therapyPlanId().value(), c.exercise().code().value(), c.version(), c.parameterSchemaVersion(),
                c.effectiveFrom(), c.status().name(), mapper.valueToTree(c.parameters()))).toList();
        return new Detail(p.therapyPlanId(), p.clinicalPatientId(), p.status(), p.configurationVersion(),
                p.dailySessionLimit(), p.dailyTargetDurationMinutes(), p.dailyHardSafetyLimitMinutes(),
                p.minimumValidDurationMinutes(), p.maximumSessionDurationMinutes(), p.minimumValidSampleCount(),
                p.dailyExercisePrescriptions(), configs);
    }
}
