package com.ledgerguard.e2e.infrastructure;

import java.util.UUID;

public final class TestIdentityFactory {

    public static final String DEFAULT_PASSWORD = "StrongSecurePassword123!";

    public TestIdentityFactory() {}

    public String createUniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID() + "@example.test";
    }

    public String createStrongPassword() {
        return DEFAULT_PASSWORD;
    }

    public UUID createIdempotencyKey() {
        return UUID.randomUUID();
    }

    public static String generateCustomerEmail() {
        return "e2e+customer+" + UUID.randomUUID() + "@example.test";
    }

    public static String generateMerchantEmail() {
        return "e2e+merchant+" + UUID.randomUUID() + "@example.test";
    }

    public static UUID nextIdempotencyKey() {
        return UUID.randomUUID();
    }

    public static UUID nextClientOperationId() {
        return UUID.randomUUID();
    }
}
