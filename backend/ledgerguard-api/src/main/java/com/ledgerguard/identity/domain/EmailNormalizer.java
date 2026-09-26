package com.ledgerguard.identity.domain;

import java.util.Locale;

/**
 * Shared utility for canonical email normalization across the identity domain.
 * Ensures consistent whitespace trimming and case folding according to {@link Locale#ROOT}.
 */
public final class EmailNormalizer {

    private EmailNormalizer() {
        // Utility class
    }

    public static String normalize(String email) {
        if (email == null) {
            return null;
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
