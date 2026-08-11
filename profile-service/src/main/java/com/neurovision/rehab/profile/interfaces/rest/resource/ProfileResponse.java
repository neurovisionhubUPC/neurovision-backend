package com.neurovision.rehab.profile.interfaces.rest.resource;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record ProfileResponse(
        UUID id,
        String profileType,
        String status,
        String firstName,
        String lastName,
        String documentNumber,
        LocalDate birthDate,
        String phone,
        Instant createdAt,
        Instant updatedAt) {}
