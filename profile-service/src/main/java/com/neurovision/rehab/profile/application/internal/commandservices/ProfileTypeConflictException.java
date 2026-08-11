package com.neurovision.rehab.profile.application.internal.commandservices;

public class ProfileTypeConflictException extends ProfileConflictException {

    public ProfileTypeConflictException() {
        super(
                "PROFILE_TYPE_CONFLICT",
                "The authenticated identity already belongs to another profile type");
    }
}
