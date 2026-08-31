package com.ledgerguard.ledger.domain;

/**
 * Supported ledger account classifications in LedgerGuard.
 */
public enum AccountType {
    CUSTOMER(NormalBalance.CREDIT, false),
    MERCHANT(NormalBalance.CREDIT, false),
    PSP_CLEARING(NormalBalance.DEBIT, true),
    PLATFORM_RESERVE(NormalBalance.DEBIT, true),
    PLATFORM_FEES(NormalBalance.CREDIT, true);

    private final NormalBalance normalBalance;
    private final boolean systemAccount;

    AccountType(NormalBalance normalBalance, boolean systemAccount) {
        this.normalBalance = normalBalance;
        this.systemAccount = systemAccount;
    }

    public NormalBalance getNormalBalance() {
        return normalBalance;
    }

    public boolean isSystemAccount() {
        return systemAccount;
    }
}
