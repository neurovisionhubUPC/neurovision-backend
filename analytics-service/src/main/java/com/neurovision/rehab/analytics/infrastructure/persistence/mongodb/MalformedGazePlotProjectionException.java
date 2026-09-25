package com.neurovision.rehab.analytics.infrastructure.persistence.mongodb;

public final class MalformedGazePlotProjectionException extends RuntimeException {
    public MalformedGazePlotProjectionException(String message, Throwable cause) {
        super(message, cause);
    }

    public MalformedGazePlotProjectionException(String message) {
        super(message);
    }
}
