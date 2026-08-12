package com.neurovision.rehab.iam.application.internal.queryservices;

import com.neurovision.rehab.iam.domain.model.aggregates.PatientInvitation;
import java.util.List;

public record PatientInvitationPage(
        List<PatientInvitation> items,
        int page,
        int size,
        long totalElements,
        int totalPages) {

    public PatientInvitationPage {
        items = List.copyOf(items);
    }
}
