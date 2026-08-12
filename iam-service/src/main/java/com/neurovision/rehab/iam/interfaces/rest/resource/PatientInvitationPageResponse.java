package com.neurovision.rehab.iam.interfaces.rest.resource;

import java.util.List;

public record PatientInvitationPageResponse(
        List<PatientInvitationResponse> items,
        int page,
        int size,
        long totalElements,
        int totalPages) {}
