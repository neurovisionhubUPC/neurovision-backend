package com.neurovision.rehab.profile.application.internal.commandservices;

public class DocumentNumberAlreadyRegisteredException extends ProfileConflictException {

    public DocumentNumberAlreadyRegisteredException() {
        super("DOCUMENT_NUMBER_ALREADY_REGISTERED", "The document number is already registered");
    }
}
