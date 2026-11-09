package com.neurovision.rehab.clinical.application.internal.queryservices;

import com.neurovision.rehab.clinical.application.ports.out.persistence.ClinicalPatientRepository;
import com.neurovision.rehab.clinical.application.ports.out.persistence.TherapySessionRepository;
import com.neurovision.rehab.clinical.application.ports.out.persistence.DoctorTherapySessionReadRepository;
import com.neurovision.rehab.clinical.application.ports.out.persistence.DoctorTherapySessionReadRepository.Detail;
import com.neurovision.rehab.clinical.domain.model.valueobjects.CognitoSubject;
import com.neurovision.rehab.clinical.domain.model.valueobjects.TherapySessionId;
import java.util.Objects;
import java.util.function.Supplier;

/** Read an owned session using the existing assignment-bound frozen detail observation. */
public final class GetPatientTherapySessionQueryService {
    private final ClinicalPatientRepository patients;
    private final TherapySessionRepository sessions;
    private final DoctorTherapySessionReadRepository details;

    public GetPatientTherapySessionQueryService(ClinicalPatientRepository patients,
            TherapySessionRepository sessions, DoctorTherapySessionReadRepository details) {
        this.patients = Objects.requireNonNull(patients);
        this.sessions = Objects.requireNonNull(sessions);
        this.details = Objects.requireNonNull(details);
    }

    public Detail handle(CognitoSubject subject, TherapySessionId id) {
        if (subject == null) throw new SecurityException();
        Objects.requireNonNull(id);
        var patient = read(() -> patients.findByPatientSubject(subject)).orElseThrow(SecurityException::new);
        var session = read(() -> sessions.findById(id)).orElseThrow(SecurityException::new);
        if (!patient.id().equals(session.clinicalPatientId())) throw new SecurityException();
        if (!id.equals(session.id())) throw new IllegalStateException("Inconsistent session reference");
        // The existing detail SELECT rechecks patient, session doctor and ACTIVE assignment together.
        // It also validates frozen configuration and progress, without locks or current plan reads.
        var detail = read(() -> details.observeDetail(patient.id().value(), session.doctorSubject().value(), id.value()))
                .orElseThrow(SecurityException::new);
        if (!patient.id().value().equals(detail.clinicalPatientId())
                || !id.value().equals(detail.session().therapySessionId()))
            throw new IllegalStateException("Inconsistent session detail observation");
        return detail;
    }

    private static <T> T read(Supplier<T> operation) {
        try { return Objects.requireNonNull(operation.get()); }
        catch (RuntimeException unavailable) {
            throw new IllegalStateException("Patient session detail unavailable", unavailable);
        }
    }
}
