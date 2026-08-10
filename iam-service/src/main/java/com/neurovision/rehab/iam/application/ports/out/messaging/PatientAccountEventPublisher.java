package com.neurovision.rehab.iam.application.ports.out.messaging;

public interface PatientAccountEventPublisher {

    void schedule(PatientAccountInvitedV1 event);
}
