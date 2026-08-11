package com.neurovision.rehab.profile.interfaces.rest.resource;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

public record CompleteProfileRequest(
        @NotBlank @Size(max = 100) String firstName,
        @NotBlank @Size(max = 100) String lastName,
        @NotBlank @Size(min = 3, max = 32) String documentNumber,
        @NotNull @PastOrPresent LocalDate birthDate,
        @NotBlank @Size(min = 7, max = 20) String phone) {}
