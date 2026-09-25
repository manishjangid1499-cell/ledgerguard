package com.ledgerguard.payment.infrastructure;

import com.ledgerguard.payment.domain.Payment;
import com.ledgerguard.payment.domain.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    Page<Payment> findByCustomerUserId(UUID userId, Pageable pageable);

    Page<Payment> findByCustomerUserIdAndStatus(UUID userId, PaymentStatus status, Pageable pageable);

    Optional<Payment> findByIdAndCustomerUserId(UUID id, UUID userId);

    @Query("SELECT p FROM Payment p WHERE p.merchantLedgerAccountId IN " +
            "(SELECT a.id FROM LedgerAccount a WHERE a.ownerUserId = :userId)")
    Page<Payment> findReceivedByUserId(@Param("userId") UUID userId, Pageable pageable);

    @Query("SELECT p FROM Payment p WHERE p.merchantLedgerAccountId IN " +
            "(SELECT a.id FROM LedgerAccount a WHERE a.ownerUserId = :userId) " +
            "AND p.status = :status")
    Page<Payment> findReceivedByUserIdAndStatus(
            @Param("userId") UUID userId,
            @Param("status") PaymentStatus status,
            Pageable pageable);

    @Query("SELECT p FROM Payment p WHERE p.id = :id AND p.merchantLedgerAccountId IN " +
            "(SELECT a.id FROM LedgerAccount a WHERE a.ownerUserId = :userId)")
    Optional<Payment> findReceivedByIdAndUserId(@Param("id") UUID id, @Param("userId") UUID userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Payment p WHERE p.id = :id")
    Optional<Payment> findByIdForUpdate(@Param("id") UUID id);

    @Query("SELECT " +
            "COALESCE(SUM(p.grossAmountMinor), 0L), " +
            "COALESCE(SUM(p.feeAmountMinor), 0L), " +
            "COALESCE(SUM(p.merchantNetAmountMinor), 0L) " +
            "FROM Payment p WHERE p.merchantLedgerAccountId IN " +
            "(SELECT a.id FROM LedgerAccount a WHERE a.ownerUserId = :userId) " +
            "AND p.status = com.ledgerguard.payment.domain.PaymentStatus.SUCCEEDED")
    List<Object[]> getReceivedPaymentTotalsByUserId(@Param("userId") UUID userId);
}
