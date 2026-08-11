package com.neurovision.rehab.profile.interfaces.events.transform;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neurovision.rehab.profile.application.internal.commandservices.RegisterInvitedPatientCommand;
import com.neurovision.rehab.profile.interfaces.events.contracts.PatientAccountInvitedV1;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Objects;

@ApplicationScoped
public class PatientAccountInvitedEventTransformer {

    private final ObjectMapper objectMapper;

    public PatientAccountInvitedEventTransformer(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    public RegisterInvitedPatientCommand toCommand(String payload) {
        try {
            var event = objectMapper.readValue(payload, PatientAccountInvitedV1.class);
            return new RegisterInvitedPatientCommand(
                    event.eventId(), event.patientSubject(), event.occurredAt());
        } catch (JsonProcessingException | IllegalArgumentException | NullPointerException exception) {
            throw new InvalidPatientAccountInvitedEventException(exception);
        }
    }
}
