package com.neurovision.rehab.iam.infrastructure.persistence.jpa.repositories;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.neurovision.rehab.iam.domain.model.aggregates.PatientInvitation;
import com.neurovision.rehab.iam.domain.model.valueobjects.CognitoSubject;
import com.neurovision.rehab.iam.domain.model.valueobjects.EmailAddress;
import com.neurovision.rehab.iam.domain.model.valueobjects.InvitationId;
import com.neurovision.rehab.iam.domain.model.valueobjects.InvitationStatus;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class DoctorPatientInvitationQueryRepositoryTest {

    @Inject
    JpaPatientInvitationRepository repository;

    @Inject
    PatientInvitationPanacheRepository entities;

    @BeforeEach
    @AfterEach
    void cleanDatabase() {
        QuarkusTransaction.requiringNew().run(entities::deleteAll);
    }

    @Test
    void shouldReturnOnlyTheDoctorsInvitationsWithPaginationAndStatusFilter() {
        var doctor = new CognitoSubject("doctor-sub");
        repository.save(invitation("11111111-1111-1111-1111-111111111111", doctor, "one@example.com", 1, false));
        repository.save(invitation("22222222-2222-2222-2222-222222222222", doctor, "two@example.com", 2, true));
        repository.save(invitation("33333333-3333-3333-3333-333333333333", doctor, "three@example.com", 3, true));
        repository.save(invitation("44444444-4444-4444-4444-444444444444", new CognitoSubject("other-doctor"), "other@example.com", 4, true));

        var firstPage = repository.findByDoctor(doctor, 0, 1, InvitationStatus.INVITED);
        var secondPage = repository.findByDoctor(doctor, 1, 1, InvitationStatus.INVITED);

        assertEquals(2, firstPage.totalElements());
        assertEquals(2, firstPage.totalPages());
        assertEquals("three@example.com", firstPage.items().getFirst().patientEmail().value());
        assertEquals("two@example.com", secondPage.items().getFirst().patientEmail().value());
    }

    private static PatientInvitation invitation(
            String id, CognitoSubject doctor, String email, long seconds, boolean invited) {
        var createdAt = Instant.parse("2026-08-11T10:00:00Z").plusSeconds(seconds);
        var invitation = PatientInvitation.initiate(
                new InvitationId(UUID.fromString(id)), doctor, new EmailAddress(email), createdAt);
        if (invited) {
            invitation.markInvited(new CognitoSubject("patient-" + id), createdAt.plusSeconds(1));
        }
        return invitation;
    }
}
