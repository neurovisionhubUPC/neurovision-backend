package com.neurovision.rehab.profile.application.internal.commandservices;

public abstract class ProfileConflictException extends RuntimeException {

    private final String code;

    protected ProfileConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
