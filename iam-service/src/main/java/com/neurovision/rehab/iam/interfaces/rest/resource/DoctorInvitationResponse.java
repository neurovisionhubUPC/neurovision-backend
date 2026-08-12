package com.neurovision.rehab.iam.interfaces.rest.resource;

import java.time.Instant;
import java.util.UUID;

public record DoctorInvitationResponse(
        UUID invitationId,
        String doctorEmail,
        String doctorSubject,
        String status,
        Instant createdAt,
        Instant updatedAt,
        Instant activatedAt) {}
