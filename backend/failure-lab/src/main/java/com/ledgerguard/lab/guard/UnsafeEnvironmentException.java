package com.ledgerguard.lab.guard;

/**
 * Thrown when an environment cannot be positively verified as a safe lab environment,
 * or when an unauthorized or potentially dangerous database connection is detected.
 */
public class UnsafeEnvironmentException extends SecurityException {

    public UnsafeEnvironmentException(String message) {
        super(message);
    }

    public UnsafeEnvironmentException(String message, Throwable cause) {
        super(message, cause);
    }
}
