package com.ledgerguard.refund.infrastructure;

import com.ledgerguard.refund.domain.Refund;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface RefundRepository extends JpaRepository<Refund, UUID> {

    List<Refund> findAllByPaymentId(UUID paymentId);

    @Query("SELECT COALESCE(SUM(r.refundAmountMinor), 0) FROM Refund r WHERE r.paymentId = :paymentId")
    long sumRefundAmountByPaymentId(@Param("paymentId") UUID paymentId);
}
