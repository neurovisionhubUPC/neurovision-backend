package com.neurovision.rehab.profile.application.internal.commandservices;

public class MedicalLicenseAlreadyRegisteredException extends ProfileConflictException {

    public MedicalLicenseAlreadyRegisteredException() {
        super("MEDICAL_LICENSE_ALREADY_REGISTERED", "The medical license is already registered");
    }
}
