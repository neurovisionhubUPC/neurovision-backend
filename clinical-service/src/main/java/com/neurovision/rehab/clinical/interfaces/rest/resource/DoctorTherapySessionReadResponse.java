package com.neurovision.rehab.clinical.interfaces.rest.resource;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.quarkus.runtime.annotations.RegisterForReflection;
import java.time.*;
import java.util.*;

@RegisterForReflection(targets={DoctorTherapySessionReadResponse.Session.class,DoctorTherapySessionReadResponse.Page.class})
public final class DoctorTherapySessionReadResponse {
    private DoctorTherapySessionReadResponse() {}
    public record Session(UUID therapySessionId,UUID therapyPlanId,String exerciseCode,String status,
            @JsonFormat(shape=JsonFormat.Shape.STRING) Instant startedAt,
            @JsonFormat(shape=JsonFormat.Shape.STRING) Instant endedAt,long activeDurationSeconds,
            @JsonFormat(shape=JsonFormat.Shape.STRING) LocalDate quotaDate,
            UUID configurationId,long configurationVersion,int parameterSchemaVersion) {}
    public record Page(UUID clinicalPatientId,List<Session> sessions,int page,int size,long total) {}
}
