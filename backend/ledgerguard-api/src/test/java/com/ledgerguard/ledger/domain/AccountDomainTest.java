package com.ledgerguard.ledger.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountDomainTest {

    @Test
    @DisplayName("Normal balance classifications match double-entry accounting rules")
    void normalBalanceClassifications() {
        assertThat(AccountType.CUSTOMER.getNormalBalance()).isEqualTo(NormalBalance.CREDIT);
        assertThat(AccountType.MERCHANT.getNormalBalance()).isEqualTo(NormalBalance.CREDIT);
        assertThat(AccountType.PSP_CLEARING.getNormalBalance()).isEqualTo(NormalBalance.DEBIT);
        assertThat(AccountType.PLATFORM_RESERVE.getNormalBalance()).isEqualTo(NormalBalance.DEBIT);
        assertThat(AccountType.PLATFORM_FEES.getNormalBalance()).isEqualTo(NormalBalance.CREDIT);
    }

    @Test
    @DisplayName("System account classification matches architecture specifications")
    void systemAccountClassification() {
        assertThat(AccountType.CUSTOMER.isSystemAccount()).isFalse();
        assertThat(AccountType.MERCHANT.isSystemAccount()).isFalse();
        assertThat(AccountType.PSP_CLEARING.isSystemAccount()).isTrue();
        assertThat(AccountType.PLATFORM_RESERVE.isSystemAccount()).isTrue();
        assertThat(AccountType.PLATFORM_FEES.isSystemAccount()).isTrue();
    }

    @Test
    @DisplayName("Customer and merchant accounts require an owner user ID")
    void userAccountsRequireOwner() {
        assertThatThrownBy(() -> LedgerAccount.createCustomerAccount(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires an owner user ID");

        assertThatThrownBy(() -> LedgerAccount.createMerchantAccount(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires an owner user ID");
    }

    @Test
    @DisplayName("System accounts must not have an owner user ID")
    void systemAccountsForbidOwner() {
        UUID ownerId = UUID.randomUUID();
        assertThatThrownBy(() -> new LedgerAccount(UUID.randomUUID(), ownerId, AccountType.PSP_CLEARING, "INR", AccountStatus.ACTIVE, java.time.Instant.now(), java.time.Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not have an owner user ID");
    }

    @Test
    @DisplayName("Non-INR currency is rejected in domain constructor")
    void nonInrCurrencyIsRejected() {
        UUID ownerId = UUID.randomUUID();
        assertThatThrownBy(() -> new LedgerAccount(UUID.randomUUID(), ownerId, AccountType.CUSTOMER, "USD", AccountStatus.ACTIVE, java.time.Instant.now(), java.time.Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Ledger currency must be INR");
    }
}
