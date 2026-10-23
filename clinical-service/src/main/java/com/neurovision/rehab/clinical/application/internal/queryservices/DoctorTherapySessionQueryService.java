package com.neurovision.rehab.clinical.application.internal.queryservices;

import com.neurovision.rehab.clinical.application.ports.out.persistence.DoctorTherapySessionReadRepository;
import com.neurovision.rehab.clinical.application.ports.out.persistence.DoctorTherapySessionReadRepository.*;
import com.neurovision.rehab.clinical.domain.model.valueobjects.CognitoSubject;
import com.neurovision.rehab.clinical.domain.model.valueobjects.TherapySessionStatus;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

public final class DoctorTherapySessionQueryService {
    private final DoctorTherapySessionReadRepository reads;
    public DoctorTherapySessionQueryService(DoctorTherapySessionReadRepository reads) {
        this.reads=Objects.requireNonNull(reads);
    }

    public Page list(String doctor, UUID patient, int page, int size, String exerciseCode,
            String status, String from, String to) {
        new CognitoSubject(doctor);
        Objects.requireNonNull(patient);
        if(page<0 || size<1 || size>100 || (long)page*size>Integer.MAX_VALUE)
            throw new IllegalArgumentException("Invalid pagination");
        if(exerciseCode!=null && !Set.of("fixation","visual-scanning","visual-search").contains(exerciseCode))
            throw new IllegalArgumentException("Invalid exercise filter");
        var filters=new Filters(exerciseCode,status==null?null:TherapySessionStatus.valueOf(status),instant(from),instant(to));
        if(filters.from()!=null && filters.to()!=null && !filters.from().isBefore(filters.to()))
            throw new IllegalArgumentException("Invalid date range");
        Observation result;
        try {
            result=Objects.requireNonNull(reads.observe(patient,doctor,filters,page*size,size));
            if(result.sessions().size()>size || result.sessions().size()>result.total())
                throw new IllegalStateException("Invalid session observation");
        } catch(RuntimeException unavailable) {
            throw new IllegalStateException("Session observation unavailable",unavailable);
        }
        if(!result.authorized()) throw new SecurityException();
        return new Page(patient,result.sessions(),page,size,result.total());
    }

    private static Instant instant(String value) {
        if(value==null) return null;
        try { return OffsetDateTime.parse(value,DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant(); }
        catch(java.time.format.DateTimeParseException invalid) {
            throw new IllegalArgumentException("Invalid date filter");
        }
    }

    public Detail detail(String doctor, UUID patient, UUID session) {
        new CognitoSubject(doctor);
        Objects.requireNonNull(patient); Objects.requireNonNull(session);
        Optional<Detail> result;
        try {
            result=Objects.requireNonNull(reads.observeDetail(patient,doctor,session));
            if(result.isPresent() && (!patient.equals(result.get().clinicalPatientId()) || !session.equals(result.get().session().therapySessionId())))
                throw new IllegalStateException("Inconsistent session detail observation");
        } catch(RuntimeException unavailable) {
            throw new IllegalStateException("Session detail unavailable",unavailable);
        }
        return result.orElseThrow(SecurityException::new);
    }

    public record Page(UUID clinicalPatientId, List<Session> sessions, int page, int size, long total) {
        public Page { sessions=List.copyOf(sessions); }
    }
}
