package com.neurovision.rehab.iam.interfaces.rest.resource;

import java.time.Instant;
import java.util.UUID;

public record PatientInvitationResponse(
        UUID invitationId,
        String patientEmail,
        String patientSubject,
        String status,
        Instant createdAt,
        Instant updatedAt) {}
