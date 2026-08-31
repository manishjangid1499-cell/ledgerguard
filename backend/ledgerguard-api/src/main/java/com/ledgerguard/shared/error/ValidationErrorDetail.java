package com.ledgerguard.shared.error;

/**
 * Encapsulates field-level validation failure information without exposing sensitive rejected values.
 *
 * @param field   the name of the invalid field
 * @param message the user-facing constraint violation message
 */
public record ValidationErrorDetail(String field, String message) {
}
