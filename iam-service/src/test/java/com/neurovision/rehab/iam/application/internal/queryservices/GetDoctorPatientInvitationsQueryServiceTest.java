package com.neurovision.rehab.iam.application.internal.queryservices;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.neurovision.rehab.iam.application.ports.out.messaging.PatientAccountInvitedV1;
import com.neurovision.rehab.iam.application.ports.out.persistence.PatientInvitationRepository;
import com.neurovision.rehab.iam.domain.model.aggregates.PatientInvitation;
import com.neurovision.rehab.iam.domain.model.valueobjects.CognitoSubject;
import com.neurovision.rehab.iam.domain.model.valueobjects.EmailAddress;
import com.neurovision.rehab.iam.domain.model.valueobjects.InvitationId;
import com.neurovision.rehab.iam.domain.model.valueobjects.InvitationStatus;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class GetDoctorPatientInvitationsQueryServiceTest {

    @Test
    void shouldForwardDoctorPaginationAndStatusToTheRepository() {
        var repository = new CapturingRepository();
        var service = new GetDoctorPatientInvitationsQueryService(repository);
        var doctor = new CognitoSubject("doctor-sub");

        var result = service.handle(
                new DoctorPatientInvitationsQuery(doctor, 2, 10, InvitationStatus.INVITED));

        assertEquals(doctor, repository.doctorSubject);
        assertEquals(2, repository.page);
        assertEquals(10, repository.size);
        assertEquals(InvitationStatus.INVITED, repository.status);
        assertEquals(2, result.page());
    }

    @Test
    void shouldRejectInvalidPagination() {
        var doctor = new CognitoSubject("doctor-sub");
        assertThrows(
                IllegalArgumentException.class,
                () -> new DoctorPatientInvitationsQuery(doctor, -1, 20, null));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DoctorPatientInvitationsQuery(doctor, 0, 101, null));
    }

    private static final class CapturingRepository implements PatientInvitationRepository {
        private CognitoSubject doctorSubject;
        private int page;
        private int size;
        private InvitationStatus status;

        @Override
        public PatientInvitationPage findByDoctor(
                CognitoSubject doctorSubject, int page, int size, InvitationStatus status) {
            this.doctorSubject = doctorSubject;
            this.page = page;
            this.size = size;
            this.status = status;
            return new PatientInvitationPage(List.of(), page, size, 0, 0);
        }

        @Override
        public boolean existsActiveByPatientEmail(EmailAddress patientEmail) {
            return false;
        }

        @Override
        public Optional<PatientInvitation> findById(InvitationId invitationId) {
            return Optional.empty();
        }

        @Override
        public Optional<PatientInvitation> findByPatientSubject(CognitoSubject patientSubject) {
            return Optional.empty();
        }

        @Override
        public void save(PatientInvitation invitation) {}

        @Override
        public void saveWithOutboxEvents(
                PatientInvitation invitation, List<PatientAccountInvitedV1> events) {}
    }
}
