package com.ledgerguard.lab.model;

import java.util.Objects;

/**
 * Result of a single financial invariant verification check.
 */
public record InvariantCheckResult(
        String invariantName,
        boolean passed,
        String expectedValue,
        String actualValue,
        String message
) {
    public InvariantCheckResult {
        Objects.requireNonNull(invariantName, "invariantName must not be null");
    }

    public static InvariantCheckResult pass(String invariantName, String details) {
        return new InvariantCheckResult(invariantName, true, "N/A", "N/A", details);
    }

    public static InvariantCheckResult pass(String invariantName, Object expected, Object actual, String details) {
        return new InvariantCheckResult(invariantName, true, String.valueOf(expected), String.valueOf(actual), details);
    }

    public static InvariantCheckResult fail(String invariantName, Object expected, Object actual, String failureReason) {
        return new InvariantCheckResult(invariantName, false, String.valueOf(expected), String.valueOf(actual), failureReason);
    }
}
