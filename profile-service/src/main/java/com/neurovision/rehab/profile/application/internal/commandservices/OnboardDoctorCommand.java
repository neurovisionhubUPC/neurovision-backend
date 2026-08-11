package com.neurovision.rehab.profile.application.internal.commandservices;

import java.time.LocalDate;

public record OnboardDoctorCommand(
        String cognitoSubject,
        String firstName,
        String lastName,
        String documentNumber,
        LocalDate birthDate,
        String phone,
        String specialty,
        String medicalLicense) {}
