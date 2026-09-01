package com.ledgerguard.outbox.application;

import com.ledgerguard.AbstractIntegrationTest;
import com.ledgerguard.identity.domain.User;
import com.ledgerguard.identity.domain.UserRepository;
import com.ledgerguard.identity.domain.UserRole;
import com.ledgerguard.identity.domain.UserStatus;
import com.ledgerguard.ledger.application.LedgerPostingService;
import com.ledgerguard.ledger.application.PostJournalCommand;
import com.ledgerguard.ledger.application.PostingLine;
import com.ledgerguard.ledger.domain.AccountStatus;
import com.ledgerguard.ledger.domain.AccountType;
import com.ledgerguard.ledger.domain.LedgerAccount;
import com.ledgerguard.ledger.domain.Money;
import com.ledgerguard.ledger.infrastructure.LedgerAccountRepository;
import com.ledgerguard.outbox.domain.OutboxEvent;
import com.ledgerguard.outbox.domain.OutboxStatus;
import com.ledgerguard.outbox.domain.TransferCompletedEvent;
import com.ledgerguard.outbox.domain.TransferCompletedPayload;
import com.ledgerguard.outbox.infrastructure.OutboxEventRepository;
import com.ledgerguard.payment.application.CreatePaymentCommand;
import com.ledgerguard.payment.application.PaymentResult;
import com.ledgerguard.payment.application.PaymentService;
import com.ledgerguard.refund.application.CreateRefundCommand;
import com.ledgerguard.refund.application.RefundResult;
import com.ledgerguard.refund.application.RefundService;
import com.ledgerguard.refund.domain.RefundLimitExceededException;
import com.ledgerguard.transfer.application.CreateTransferCommand;
import com.ledgerguard.transfer.application.TransferResult;
import com.ledgerguard.transfer.application.TransferService;
import com.ledgerguard.transfer.domain.InsufficientFundsException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutboxServiceIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private OutboxService outboxService;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private TransferService transferService;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private RefundService refundService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private LedgerAccountRepository ledgerAccountRepository;

    @Autowired
    private LedgerPostingService ledgerPostingService;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void ensureSingleActiveFeeAccount() {
        List<LedgerAccount> feeAccounts = ledgerAccountRepository.findAllByAccountType(AccountType.PLATFORM_FEES);
        for (LedgerAccount fa : feeAccounts) {
            if (fa.getStatus() == AccountStatus.ACTIVE) {
                fa.close(Instant.now());
                ledgerAccountRepository.saveAndFlush(fa);
            }
        }
        LedgerAccount canonicalFee = LedgerAccount.createSystemAccount(AccountType.PLATFORM_FEES);
        ledgerAccountRepository.saveAndFlush(canonicalFee);
    }

    @Test
    @DisplayName("OutboxService.append outside an active transaction throws IllegalTransactionStateException (MANDATORY propagation)")
    void appendOutsideTransactionThrowsException() {
        TransferCompletedEvent event = TransferCompletedEvent.of(
                UUID.randomUUID(),
                UUID.randomUUID(),
                Instant.now(),
                new TransferCompletedPayload(
                        UUID.randomUUID().toString(),
                        UUID.randomUUID().toString(),
                        UUID.randomUUID().toString(),
                        "10000",
                        "INR",
                        UUID.randomUUID().toString()
                )
        );

        assertThatThrownBy(() -> outboxService.append(event))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    @DisplayName("OutboxService.append inside transaction persists PENDING event; transaction rollback removes outbox row")
    void transactionRollbackRemovesOutboxRow() {
        UUID eventId = UUID.randomUUID();

        assertThatThrownBy(() -> transactionTemplate.execute(status -> {
            outboxService.append(TransferCompletedEvent.of(
                    eventId,
                    UUID.randomUUID(),
                    Instant.now(),
                    new TransferCompletedPayload(
                            UUID.randomUUID().toString(),
                            UUID.randomUUID().toString(),
                            UUID.randomUUID().toString(),
                            "10000",
                            "INR",
                            UUID.randomUUID().toString()
                    )
            ));
            throw new RuntimeException("Deliberate test rollback");
        })).hasMessageContaining("Deliberate test rollback");

        // Assert outbox row did not commit
        assertThat(outboxEventRepository.findById(eventId)).isEmpty();
    }

    @Test
    @DisplayName("Successful Transfer: emits exactly 1 TRANSFER_COMPLETED outbox event; replay emits 0 duplicate events")
    void successfulTransferEmitsOutboxEventAndReplayDeduplicates() throws Exception {
        User sender = createTestUser("sender.outbox." + UUID.randomUUID() + "@example.com", UserRole.CUSTOMER);
        User recipient = createTestUser("recipient.outbox." + UUID.randomUUID() + "@example.com", UserRole.CUSTOMER);

        LedgerAccount sourceWallet = createWallet(sender.getId(), AccountType.CUSTOMER);
        LedgerAccount destWallet = createWallet(recipient.getId(), AccountType.CUSTOMER);
        fundWallet(sourceWallet.getId(), 50000L);

        String idempotencyKey = "tx-outbox-" + UUID.randomUUID();

        // 1. First execution
        TransferResult result1 = transferService.createTransfer(new CreateTransferCommand(
                sender.getId(),
                destWallet.getId(),
                Money.inr(15000L),
                idempotencyKey
        ));

        assertThat(result1.replayed()).isFalse();

        // Verify outbox row in database
        List<OutboxEvent> events = outboxEventRepository.findAll().stream()
                .filter(e -> e.getAggregateId().equals(result1.transferId()))
                .toList();

        assertThat(events).hasSize(1);
        OutboxEvent event = events.get(0);
        assertThat(event.getAggregateType()).isEqualTo("TRANSFER");
        assertThat(event.getEventType()).isEqualTo("TRANSFER_COMPLETED");
        assertThat(event.getEventVersion()).isEqualTo(1);
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(event.getPublishedAt()).isNull();

        JsonNode payloadNode = objectMapper.readTree(event.getPayload());
        assertThat(payloadNode.get("transferId").asText()).isEqualTo(result1.transferId().toString());
        assertThat(payloadNode.get("sourceLedgerAccountId").asText()).isEqualTo(sourceWallet.getId().toString());
        assertThat(payloadNode.get("destinationLedgerAccountId").asText()).isEqualTo(destWallet.getId().toString());
        assertThat(payloadNode.get("amountMinor").asText()).isEqualTo("15000");
        assertThat(payloadNode.get("currency").asText()).isEqualTo("INR");
        assertThat(payloadNode.get("journalTransactionId").asText()).isEqualTo(result1.journalTransactionId().toString());

        // 2. Idempotent replay with same key
        TransferResult result2 = transferService.createTransfer(new CreateTransferCommand(
                sender.getId(),
                destWallet.getId(),
                Money.inr(15000L),
                idempotencyKey
        ));

        assertThat(result2.replayed()).isTrue();
        assertThat(result2.transferId()).isEqualTo(result1.transferId());

        // Assert no duplicate event was created
        List<OutboxEvent> eventsAfterReplay = outboxEventRepository.findAll().stream()
                .filter(e -> e.getAggregateId().equals(result1.transferId()))
                .toList();
        assertThat(eventsAfterReplay).hasSize(1);
    }

    @Test
    @DisplayName("Transfer failure (insufficient funds): commits 0 outbox events and 0 transfers")
    void failedTransferEmitsZeroOutboxEvents() {
        User sender = createTestUser("sender.fail." + UUID.randomUUID() + "@example.com", UserRole.CUSTOMER);
        User recipient = createTestUser("recipient.fail." + UUID.randomUUID() + "@example.com", UserRole.CUSTOMER);

        LedgerAccount sourceWallet = createWallet(sender.getId(), AccountType.CUSTOMER);
        LedgerAccount destWallet = createWallet(recipient.getId(), AccountType.CUSTOMER);
        fundWallet(sourceWallet.getId(), 5000L);

        String idempotencyKey = "tx-fail-" + UUID.randomUUID();

        assertThatThrownBy(() -> transferService.createTransfer(new CreateTransferCommand(
                sender.getId(),
                destWallet.getId(),
                Money.inr(10000L),
                idempotencyKey
        ))).isInstanceOf(InsufficientFundsException.class);

        // Assert 0 outbox events
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE event_type = 'TRANSFER_COMPLETED' AND payload->>'amountMinor' = '10000'",
                Integer.class
        );
        assertThat(count).isEqualTo(0);
    }

    @Test
    @DisplayName("Successful Payment: emits exactly 1 PAYMENT_SUCCEEDED outbox event with fee split; replay deduplicates")
    void successfulPaymentEmitsOutboxEventAndReplayDeduplicates() throws Exception {
        User customer = createTestUser("cust.pay." + UUID.randomUUID() + "@example.com", UserRole.CUSTOMER);
        User merchant = createTestUser("merch.pay." + UUID.randomUUID() + "@example.com", UserRole.MERCHANT);

        LedgerAccount customerWallet = createWallet(customer.getId(), AccountType.CUSTOMER);
        LedgerAccount merchantWallet = createWallet(merchant.getId(), AccountType.MERCHANT);
        fundWallet(customerWallet.getId(), 50000L);

        String idempotencyKey = "pay-outbox-" + UUID.randomUUID();

        // 1. First execution (10000 gross -> 100 fee -> 9900 net)
        PaymentResult result1 = paymentService.createPayment(new CreatePaymentCommand(
                customer.getId(),
                idempotencyKey,
                merchantWallet.getId(),
                Money.inr(10000L)
        ));

        assertThat(result1.replayed()).isFalse();

        List<OutboxEvent> events = outboxEventRepository.findAll().stream()
                .filter(e -> e.getAggregateId().equals(result1.paymentId()))
                .toList();

        assertThat(events).hasSize(1);
        OutboxEvent event = events.get(0);
        assertThat(event.getAggregateType()).isEqualTo("PAYMENT");
        assertThat(event.getEventType()).isEqualTo("PAYMENT_SUCCEEDED");
        assertThat(event.getEventVersion()).isEqualTo(1);
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(event.getPublishedAt()).isNull();

        JsonNode payloadNode = objectMapper.readTree(event.getPayload());
        assertThat(payloadNode.get("paymentId").asText()).isEqualTo(result1.paymentId().toString());
        assertThat(payloadNode.get("customerLedgerAccountId").asText()).isEqualTo(customerWallet.getId().toString());
        assertThat(payloadNode.get("merchantLedgerAccountId").asText()).isEqualTo(merchantWallet.getId().toString());
        assertThat(payloadNode.get("grossAmountMinor").asText()).isEqualTo("10000");
        assertThat(payloadNode.get("feeAmountMinor").asText()).isEqualTo("100");
        assertThat(payloadNode.get("merchantNetAmountMinor").asText()).isEqualTo("9900");
        assertThat(payloadNode.get("currency").asText()).isEqualTo("INR");
        assertThat(payloadNode.get("journalTransactionId").asText()).isEqualTo(result1.journalTransactionId().toString());

        // 2. Replay with same key
        PaymentResult result2 = paymentService.createPayment(new CreatePaymentCommand(
                customer.getId(),
                idempotencyKey,
                merchantWallet.getId(),
                Money.inr(10000L)
        ));

        assertThat(result2.replayed()).isTrue();
        assertThat(result2.paymentId()).isEqualTo(result1.paymentId());

        List<OutboxEvent> eventsAfterReplay = outboxEventRepository.findAll().stream()
                .filter(e -> e.getAggregateId().equals(result1.paymentId()))
                .toList();
        assertThat(eventsAfterReplay).hasSize(1);
    }

    @Test
    @DisplayName("Successful Refund: emits exactly 1 REFUND_COMPLETED outbox event; replay deduplicates; over-refund fails with 0 events")
    void successfulRefundEmitsOutboxEventAndReplayDeduplicates() throws Exception {
        User customer = createTestUser("cust.ref." + UUID.randomUUID() + "@example.com", UserRole.CUSTOMER);
        User merchant = createTestUser("merch.ref." + UUID.randomUUID() + "@example.com", UserRole.MERCHANT);

        LedgerAccount customerWallet = createWallet(customer.getId(), AccountType.CUSTOMER);
        LedgerAccount merchantWallet = createWallet(merchant.getId(), AccountType.MERCHANT);
        fundWallet(customerWallet.getId(), 50000L);

        // Execute payment 10000
        PaymentResult payment = paymentService.createPayment(new CreatePaymentCommand(
                customer.getId(),
                "pay-for-ref-" + UUID.randomUUID(),
                merchantWallet.getId(),
                Money.inr(10000L)
        ));

        String refundIdempotencyKey = "ref-outbox-" + UUID.randomUUID();

        // 1. First execution: partial refund 5000
        RefundResult refResult1 = refundService.createRefund(new CreateRefundCommand(
                merchant.getId(),
                refundIdempotencyKey,
                payment.paymentId(),
                Money.inr(5000L)
        ));

        assertThat(refResult1.replayed()).isFalse();

        List<OutboxEvent> events = outboxEventRepository.findAll().stream()
                .filter(e -> e.getAggregateId().equals(refResult1.refundId()))
                .toList();

        assertThat(events).hasSize(1);
        OutboxEvent event = events.get(0);
        assertThat(event.getAggregateType()).isEqualTo("REFUND");
        assertThat(event.getEventType()).isEqualTo("REFUND_COMPLETED");
        assertThat(event.getEventVersion()).isEqualTo(1);
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(event.getPublishedAt()).isNull();

        JsonNode payloadNode = objectMapper.readTree(event.getPayload());
        assertThat(payloadNode.get("refundId").asText()).isEqualTo(refResult1.refundId().toString());
        assertThat(payloadNode.get("paymentId").asText()).isEqualTo(payment.paymentId().toString());
        assertThat(payloadNode.get("refundAmountMinor").asText()).isEqualTo("5000");
        assertThat(payloadNode.get("merchantDebitAmountMinor").asText()).isEqualTo(String.valueOf(refResult1.merchantDebitAmountMinor()));
        assertThat(payloadNode.get("feeDebitAmountMinor").asText()).isEqualTo(String.valueOf(refResult1.feeDebitAmountMinor()));
        assertThat(payloadNode.get("currency").asText()).isEqualTo("INR");
        assertThat(payloadNode.get("journalTransactionId").asText()).isEqualTo(refResult1.journalTransactionId().toString());

        // 2. Replay same refund key
        RefundResult refResult2 = refundService.createRefund(new CreateRefundCommand(
                merchant.getId(),
                refundIdempotencyKey,
                payment.paymentId(),
                Money.inr(5000L)
        ));

        assertThat(refResult2.replayed()).isTrue();
        assertThat(refResult2.refundId()).isEqualTo(refResult1.refundId());

        List<OutboxEvent> eventsAfterReplay = outboxEventRepository.findAll().stream()
                .filter(e -> e.getAggregateId().equals(refResult1.refundId()))
                .toList();
        assertThat(eventsAfterReplay).hasSize(1);

        // 3. Over-refund attempt: 6000 (exceeds remaining 5000 capacity) -> fails with 0 new outbox events
        assertThatThrownBy(() -> refundService.createRefund(new CreateRefundCommand(
                merchant.getId(),
                "ref-over-" + UUID.randomUUID(),
                payment.paymentId(),
                Money.inr(6000L)
        ))).isInstanceOf(RefundLimitExceededException.class);

        Integer overCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE payload->>'refundAmountMinor' = '6000'",
                Integer.class
        );
        assertThat(overCount).isEqualTo(0);
    }

    @Test
    @DisplayName("Monetary amounts exceeding JavaScript MAX_SAFE_INTEGER are preserved as exact decimal strings in outbox JSONB")
    void payloadPreservesLargeValuesAsStrings() throws Exception {
        User sender = createTestUser("sender.large." + UUID.randomUUID() + "@example.com", UserRole.CUSTOMER);
        User recipient = createTestUser("recipient.large." + UUID.randomUUID() + "@example.com", UserRole.CUSTOMER);

        LedgerAccount sourceWallet = createWallet(sender.getId(), AccountType.CUSTOMER);
        LedgerAccount destWallet = createWallet(recipient.getId(), AccountType.CUSTOMER);

        // 9,007,199,254,740,995 > 9,007,199,254,740,991 (MAX_SAFE_INTEGER)
        long largeAmount = 9007199254740995L;
        fundWallet(sourceWallet.getId(), largeAmount);

        TransferResult result = transferService.createTransfer(new CreateTransferCommand(
                sender.getId(),
                destWallet.getId(),
                Money.inr(largeAmount),
                "tx-large-" + UUID.randomUUID()
        ));

        OutboxEvent event = outboxEventRepository.findAll().stream()
                .filter(e -> e.getAggregateId().equals(result.transferId()))
                .findFirst()
                .orElseThrow();

        JsonNode payload = objectMapper.readTree(event.getPayload());
        assertThat(payload.get("amountMinor").asText()).isEqualTo("9007199254740995");
        assertThat(payload.get("amountMinor").isTextual()).isTrue();
    }

    private User createTestUser(String email, UserRole role) {
        User user = new User(UUID.randomUUID(), email, "$2a$10$dummyHashValueForTestingOnly", role, UserStatus.ACTIVE);
        return userRepository.saveAndFlush(user);
    }

    private LedgerAccount createWallet(UUID ownerUserId, AccountType type) {
        LedgerAccount account = (type == AccountType.CUSTOMER)
                ? LedgerAccount.createCustomerAccount(ownerUserId)
                : LedgerAccount.createMerchantAccount(ownerUserId);
        return ledgerAccountRepository.saveAndFlush(account);
    }

    private void fundWallet(UUID walletAccountId, long amountMinor) {
        LedgerAccount reserve = getOrCreateSystemAccount(AccountType.PLATFORM_RESERVE);
        ledgerPostingService.post(PostJournalCommand.of(
                PostingLine.debit(reserve.getId(), amountMinor),
                PostingLine.credit(walletAccountId, amountMinor)
        ));
    }

    private LedgerAccount getOrCreateSystemAccount(AccountType type) {
        List<LedgerAccount> existing = ledgerAccountRepository.findAll().stream()
                .filter(a -> a.getAccountType() == type && a.getOwnerUserId() == null && a.getStatus() == AccountStatus.ACTIVE)
                .toList();
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        LedgerAccount account = LedgerAccount.createSystemAccount(type);
        return ledgerAccountRepository.saveAndFlush(account);
    }
}
