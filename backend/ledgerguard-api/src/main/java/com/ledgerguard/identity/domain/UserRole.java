package com.ledgerguard.identity.domain;

public enum UserRole {
    CUSTOMER,
    MERCHANT,
    OPS;

    public String toAuthority() {
        return "ROLE_" + name();
    }
}
