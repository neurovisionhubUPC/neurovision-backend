package com.neurovision.rehab.profile.interfaces.rest.resource;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

public record OnboardDoctorRequest(
        @NotBlank @Size(max = 100) String firstName,
        @NotBlank @Size(max = 100) String lastName,
        @Size(min = 3, max = 32) String documentNumber,
        @PastOrPresent LocalDate birthDate,
        @Size(min = 7, max = 20) String phone,
        @Size(min = 2, max = 100) String specialty,
        @Size(max = 64) String medicalLicense) {}
