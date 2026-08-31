package com.ledgerguard.ledger.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Represents a double-entry journal transaction.
 * A journal transaction groups balanced debit and credit entries.
 * Once POSTED, a journal transaction is immutable financial history.
 */
@Entity
@Table(name = "journal_transactions")
public class JournalTransaction {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private JournalStatus status = JournalStatus.DRAFT;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "INR";

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "posted_at")
    private Instant postedAt;

    @OneToMany(mappedBy = "journalTransaction", fetch = FetchType.LAZY)
    private List<JournalEntry> entries = new ArrayList<>();

    protected JournalTransaction() {
        // JPA requirement
    }

    public JournalTransaction(UUID id, JournalStatus status, String currency, Instant createdAt, Instant postedAt) {
        this.id = Objects.requireNonNull(id, "Transaction ID must not be null");
        this.status = Objects.requireNonNull(status, "Status must not be null");
        this.currency = Objects.requireNonNull(currency, "Currency must not be null");
        this.createdAt = Objects.requireNonNull(createdAt, "Created at must not be null");

        if (!"INR".equals(currency)) {
            throw new IllegalArgumentException("Ledger transaction currency must be INR");
        }

        if (status == JournalStatus.POSTED && postedAt == null) {
            throw new IllegalArgumentException("POSTED journal transaction requires a non-null postedAt timestamp");
        }
        if (status == JournalStatus.DRAFT && postedAt != null) {
            throw new IllegalArgumentException("DRAFT journal transaction must not have a postedAt timestamp");
        }

        this.postedAt = postedAt;
    }

    public static JournalTransaction createDraft() {
        return new JournalTransaction(UUID.randomUUID(), JournalStatus.DRAFT, "INR", Instant.now(), null);
    }

    public static JournalTransaction createDraft(UUID id) {
        return new JournalTransaction(id, JournalStatus.DRAFT, "INR", Instant.now(), null);
    }

    public UUID getId() {
        return id;
    }

    public JournalStatus getStatus() {
        return status;
    }

    public String getCurrency() {
        return currency;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPostedAt() {
        return postedAt;
    }

    public List<JournalEntry> getEntries() {
        return Collections.unmodifiableList(entries);
    }

    public void post(Instant postedAt) {
        if (this.status == JournalStatus.POSTED) {
            throw new IllegalStateException("Journal transaction is already POSTED: " + id);
        }
        this.status = JournalStatus.POSTED;
        this.postedAt = Objects.requireNonNull(postedAt, "Posted at timestamp must not be null");
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        JournalTransaction that = (JournalTransaction) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
