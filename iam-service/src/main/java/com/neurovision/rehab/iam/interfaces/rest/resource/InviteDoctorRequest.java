package com.neurovision.rehab.iam.interfaces.rest.resource;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record InviteDoctorRequest(
        @NotBlank(message = "Doctor email is required")
        @Email(message = "Doctor email must be valid")
        @Size(max = 254, message = "Doctor email must not exceed 254 characters")
        String doctorEmail) {}
