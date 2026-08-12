package com.neurovision.rehab.iam.application.ports.out.messaging;

import com.neurovision.rehab.iam.domain.model.aggregates.DoctorInvitation;
import java.util.List;

public interface DoctorInvitationOutbox {

    void saveAtomically(
            DoctorInvitation invitation, List<DoctorAccountInvitedV1> events);
}
