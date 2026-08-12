package com.neurovision.rehab.iam.application.internal.queryservices;

import com.neurovision.rehab.iam.application.ports.out.persistence.PatientInvitationRepository;
import java.util.Objects;

public final class GetDoctorPatientInvitationsQueryService {

    private final PatientInvitationRepository invitations;

    public GetDoctorPatientInvitationsQueryService(PatientInvitationRepository invitations) {
        this.invitations = Objects.requireNonNull(invitations);
    }

    public PatientInvitationPage handle(DoctorPatientInvitationsQuery query) {
        Objects.requireNonNull(query, "Query must not be null");
        return invitations.findByDoctor(
                query.doctorSubject(), query.page(), query.size(), query.status());
    }
}
