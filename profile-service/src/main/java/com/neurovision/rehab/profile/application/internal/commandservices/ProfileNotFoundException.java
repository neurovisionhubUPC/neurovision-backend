package com.neurovision.rehab.profile.application.internal.commandservices;

public final class ProfileNotFoundException extends RuntimeException {

    public ProfileNotFoundException() {
        super("Profile was not found for the authenticated user");
    }
}
