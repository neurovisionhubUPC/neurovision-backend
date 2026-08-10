package com.neurovision.rehab.iam.interfaces.rest.resource;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record InvitePatientRequest(
        @NotBlank(message = "Patient email is required")
        @Email(message = "Patient email must be valid")
        @Size(max = 254, message = "Patient email must not exceed 254 characters")
        String patientEmail) {}
