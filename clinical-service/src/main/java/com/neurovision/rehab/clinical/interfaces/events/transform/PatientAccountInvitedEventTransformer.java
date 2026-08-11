package com.neurovision.rehab.clinical.interfaces.events.transform;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neurovision.rehab.clinical.application.internal.commandservices.RegisterInvitedClinicalPatientCommand;
import com.neurovision.rehab.clinical.interfaces.events.contracts.PatientAccountInvitedV1;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Objects;

@ApplicationScoped
public class PatientAccountInvitedEventTransformer {

    private final ObjectMapper objectMapper;

    public PatientAccountInvitedEventTransformer(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    public RegisterInvitedClinicalPatientCommand toCommand(String payload) {
        try {
            var event = objectMapper.readValue(payload, PatientAccountInvitedV1.class);
            return new RegisterInvitedClinicalPatientCommand(
                    event.eventId(),
                    event.invitedByDoctor(),
                    event.patientSubject(),
                    event.occurredAt());
        } catch (JsonProcessingException | IllegalArgumentException | NullPointerException exception) {
            throw new InvalidPatientAccountInvitedEventException(exception);
        }
    }
}
