package com.neurovision.rehab.clinical.interfaces.rest.transform;

import com.neurovision.rehab.clinical.application.internal.queryservices.DoctorTherapySessionQueryService;
import com.neurovision.rehab.clinical.interfaces.rest.resource.DoctorTherapySessionReadResponse;
import com.neurovision.rehab.clinical.application.ports.out.persistence.DoctorTherapySessionReadRepository.Detail;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class DoctorTherapySessionReadRestAssembler {
    private DoctorTherapySessionReadRestAssembler() {}
    public static DoctorTherapySessionReadResponse.Detail detail(Detail result,ObjectMapper mapper) {
        var s=result.session(); var limits=result.sessionLimits();
        try {
            return new DoctorTherapySessionReadResponse.Detail(result.clinicalPatientId(),s.therapySessionId(),s.therapyPlanId(),
                    s.exerciseCode(),s.status(),s.startedAt(),s.endedAt(),s.activeDurationSeconds(),s.quotaDate(),
                    s.configurationId(),s.configurationVersion(),s.parameterSchemaVersion(),mapper.valueToTree(result.configurationParameters()),
                    new DoctorTherapySessionReadResponse.SessionLimits(limits.minimumValidDurationMinutes(),limits.maximumSessionDurationMinutes(),limits.minimumValidSampleCount()));
        } catch(RuntimeException unavailable) {
            throw new IllegalStateException("Session detail representation unavailable",unavailable);
        }
    }
    public static DoctorTherapySessionReadResponse.Page page(DoctorTherapySessionQueryService.Page result) {
        return new DoctorTherapySessionReadResponse.Page(result.clinicalPatientId(),result.sessions().stream()
                .map(s->new DoctorTherapySessionReadResponse.Session(s.therapySessionId(),s.therapyPlanId(),
                        s.exerciseCode(),s.status(),s.startedAt(),s.endedAt(),s.activeDurationSeconds(),s.quotaDate(),
                        s.configurationId(),s.configurationVersion(),s.parameterSchemaVersion())).toList(),
                result.page(),result.size(),result.total());
    }
}
