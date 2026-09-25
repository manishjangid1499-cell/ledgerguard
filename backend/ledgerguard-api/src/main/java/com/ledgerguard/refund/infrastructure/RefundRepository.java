package com.ledgerguard.refund.infrastructure;

import com.ledgerguard.refund.domain.Refund;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface RefundRepository extends JpaRepository<Refund, UUID> {

    Page<Refund> findByPaymentId(UUID paymentId, Pageable pageable);

    List<Refund> findAllByPaymentId(UUID paymentId);

    @Query("SELECT COALESCE(SUM(r.refundAmountMinor), 0) FROM Refund r WHERE r.paymentId = :paymentId")
    long sumRefundAmountByPaymentId(@Param("paymentId") UUID paymentId);

    @Query("SELECT r.paymentId, COALESCE(SUM(r.refundAmountMinor), 0) FROM Refund r WHERE r.paymentId IN :paymentIds GROUP BY r.paymentId")
    List<Object[]> sumRefundAmountByPaymentIds(@Param("paymentIds") Collection<UUID> paymentIds);

    @Query("SELECT COALESCE(SUM(r.refundAmountMinor), 0) FROM Refund r WHERE r.paymentId IN " +
            "(SELECT p.id FROM Payment p WHERE p.merchantLedgerAccountId IN " +
            "(SELECT a.id FROM LedgerAccount a WHERE a.ownerUserId = :userId))")
    long sumTotalRefundedForMerchant(@Param("userId") UUID userId);
}
