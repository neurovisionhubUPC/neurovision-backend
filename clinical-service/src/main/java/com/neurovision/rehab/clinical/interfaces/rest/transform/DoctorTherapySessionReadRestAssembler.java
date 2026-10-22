package com.neurovision.rehab.clinical.interfaces.rest.transform;

import com.neurovision.rehab.clinical.application.internal.queryservices.DoctorTherapySessionQueryService;
import com.neurovision.rehab.clinical.interfaces.rest.resource.DoctorTherapySessionReadResponse;

public final class DoctorTherapySessionReadRestAssembler {
    private DoctorTherapySessionReadRestAssembler() {}
    public static DoctorTherapySessionReadResponse.Page page(DoctorTherapySessionQueryService.Page result) {
        return new DoctorTherapySessionReadResponse.Page(result.clinicalPatientId(),result.sessions().stream()
                .map(s->new DoctorTherapySessionReadResponse.Session(s.therapySessionId(),s.therapyPlanId(),
                        s.exerciseCode(),s.status(),s.startedAt(),s.endedAt(),s.activeDurationSeconds(),s.quotaDate(),
                        s.configurationId(),s.configurationVersion(),s.parameterSchemaVersion())).toList(),
                result.page(),result.size(),result.total());
    }
}
