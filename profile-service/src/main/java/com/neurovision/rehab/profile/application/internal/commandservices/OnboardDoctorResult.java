package com.neurovision.rehab.profile.application.internal.commandservices;

import com.neurovision.rehab.profile.domain.model.aggregates.DoctorProfile;
import com.neurovision.rehab.profile.domain.model.aggregates.UserProfile;

public record OnboardDoctorResult(UserProfile userProfile, DoctorProfile doctorProfile) {}
