package com.ledgerguard.reconciliation.application;

import com.ledgerguard.reconciliation.api.SnapshotRepairResponse;
import com.ledgerguard.reconciliation.domain.ReconciliationCase;
import com.ledgerguard.reconciliation.domain.ReconciliationCaseStatus;
import com.ledgerguard.reconciliation.domain.ReconciliationClassification;
import com.ledgerguard.reconciliation.domain.ReconciliationConflictException;
import com.ledgerguard.reconciliation.domain.ReconciliationItem;
import com.ledgerguard.reconciliation.domain.ReconciliationLevel;
import com.ledgerguard.reconciliation.domain.ReconciliationNotFoundException;
import com.ledgerguard.reconciliation.domain.ReconciliationProblemType;
import com.ledgerguard.reconciliation.domain.ReconciliationResolutionAction;
import com.ledgerguard.reconciliation.infrastructure.ReconciliationCaseRepository;
import com.ledgerguard.reconciliation.infrastructure.ReconciliationItemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Service executing automated repair of derived balance snapshots.
 * <p>
 * Key safety invariants:
 * <ul>
 *   <li>Only {@code SNAPSHOT_MISMATCH} items on {@code LEDGER_ACCOUNT} are eligible.</li>
 *   <li>Never trusts detection-time {@code item.expected_value}; always recomputes current truth
 *       from immutable {@code POSTED} journal entries.</li>
 *   <li>Locks target {@code ledger_balance_snapshots} row {@code FOR UPDATE} before reconstruction,
 *       serializing perfectly with V3 posting triggers and preventing lost updates.</li>
 *   <li>Rejects missing snapshot rows with 409 Conflict (no auto-creation).</li>
 *   <li>Rejects reconstruction values exceeding signed 64-bit BIGINT range.</li>
 *   <li>Preserves projection timestamp semantics using {@code MAX(posted_at)} / account {@code created_at}.</li>
 * </ul>
 */
@Service
public class SnapshotAutoRepairService {

    private static final Logger log = LoggerFactory.getLogger(SnapshotAutoRepairService.class);

    private static final BigDecimal BIGINT_MIN = BigDecimal.valueOf(Long.MIN_VALUE);
    private static final BigDecimal BIGINT_MAX = BigDecimal.valueOf(Long.MAX_VALUE);

    private static final String LOCK_SNAPSHOT_SQL = """
            SELECT balance_minor, updated_at
            FROM ledger_balance_snapshots
            WHERE ledger_account_id = ?
            FOR UPDATE
            """;

    private static final String RECONSTRUCT_SQL = """
            SELECT
                la.id                                           AS ledger_account_id,
                COALESCE(
                    SUM(
                        CASE
                            WHEN la.account_type IN ('CUSTOMER', 'MERCHANT', 'PLATFORM_FEES') THEN
                                CASE WHEN pe.direction = 'CREDIT'
                                     THEN  pe.amount_minor::NUMERIC
                                     ELSE -pe.amount_minor::NUMERIC END
                            ELSE
                                CASE WHEN pe.direction = 'DEBIT'
                                     THEN  pe.amount_minor::NUMERIC
                                     ELSE -pe.amount_minor::NUMERIC END
                        END
                    ), 0::NUMERIC
                )                                               AS reconstructed_balance,
                COALESCE(MAX(pe.posted_at), la.created_at)      AS projection_updated_at
            FROM ledger_accounts la
            LEFT JOIN (
                SELECT
                    je.ledger_account_id,
                    je.direction,
                    je.amount_minor,
                    jt.posted_at
                FROM journal_entries je
                JOIN journal_transactions jt
                  ON jt.id = je.journal_transaction_id
                 AND jt.status = 'POSTED'
            ) pe ON pe.ledger_account_id = la.id
            WHERE la.id = ?
            GROUP BY la.id, la.account_type, la.created_at
            """;

    private static final String UPDATE_SNAPSHOT_SQL = """
            UPDATE ledger_balance_snapshots
            SET balance_minor = ?, updated_at = ?
            WHERE ledger_account_id = ?
            """;

    private final ReconciliationCaseRepository caseRepository;
    private final ReconciliationItemRepository itemRepository;
    private final JdbcTemplate jdbcTemplate;

    public SnapshotAutoRepairService(ReconciliationCaseRepository caseRepository,
                                     ReconciliationItemRepository itemRepository,
                                     JdbcTemplate jdbcTemplate) {
        this.caseRepository = caseRepository;
        this.itemRepository = itemRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public SnapshotRepairResponse repairSnapshot(UUID caseId, UUID actorUserId) {
        Objects.requireNonNull(caseId, "Case ID must not be null");
        Objects.requireNonNull(actorUserId, "Actor user ID must not be null");

        // 1. Lock case row FOR UPDATE
        ReconciliationCase reconCase = caseRepository.findByIdForUpdate(caseId)
                .orElseThrow(() -> new ReconciliationNotFoundException("Reconciliation case not found: " + caseId));

        ReconciliationItem item = itemRepository.findById(reconCase.getReconciliationItemId())
                .orElseThrow(() -> new ReconciliationNotFoundException("Reconciliation item not found for case: " + caseId));

        // 2. Terminal idempotency check
        if (reconCase.getStatus() == ReconciliationCaseStatus.RESOLVED) {
            if (reconCase.getResolutionAction() == ReconciliationResolutionAction.SNAPSHOT_REPAIRED
                    || reconCase.getResolutionAction() == ReconciliationResolutionAction.ALREADY_CONSISTENT) {
                log.info("Idempotent repair replay on case {} by actor {}", caseId, actorUserId);
                return buildExistingRepairResponse(reconCase, item);
            }
            throw new ReconciliationConflictException("Case " + caseId + " is already resolved with non-repair action: "
                    + reconCase.getResolutionAction());
        }

        // 3. Ownership check if IN_REVIEW
        if (reconCase.getStatus() == ReconciliationCaseStatus.IN_REVIEW) {
            if (!actorUserId.equals(reconCase.getAssignedToUserId())) {
                throw new ReconciliationConflictException("Case " + caseId + " is claimed by another operator; cannot repair");
            }
        }

        // 4. Eligibility check
        if (item.getLevel() != ReconciliationLevel.SNAPSHOT_CONSISTENCY
                || item.getProblemType() != ReconciliationProblemType.SNAPSHOT_MISMATCH
                || !"LEDGER_ACCOUNT".equals(item.getEntityType())) {
            throw new ReconciliationConflictException("Reconciliation item " + item.getId()
                    + " with problem type " + item.getProblemType() + " is not eligible for snapshot auto-repair");
        }

        UUID accountId = item.getEntityId();

        // 5. Lock existing ledger_balance_snapshots row FOR UPDATE
        Long currentBalanceMinor;
        Instant currentUpdatedAt;
        try {
            var row = jdbcTemplate.queryForMap(LOCK_SNAPSHOT_SQL, accountId);
            currentBalanceMinor = ((Number) row.get("balance_minor")).longValue();
            currentUpdatedAt = ((Timestamp) row.get("updated_at")).toInstant();
        } catch (EmptyResultDataAccessException e) {
            log.warn("Snapshot row missing for account {} during repair on case {}", accountId, caseId);
            throw new ReconciliationConflictException("Snapshot row missing for ledger account " + accountId + "; cannot auto-repair");
        }

        // 6. Reconstruct CURRENT expected posted balance from immutable POSTED journal history
        BigDecimal reconstructedBalance;
        Instant projectionUpdatedAt;
        try {
            var reconRow = jdbcTemplate.queryForMap(RECONSTRUCT_SQL, accountId);
            reconstructedBalance = (BigDecimal) reconRow.get("reconstructed_balance");
            projectionUpdatedAt = ((Timestamp) reconRow.get("projection_updated_at")).toInstant();
        } catch (EmptyResultDataAccessException e) {
            throw new ReconciliationNotFoundException("Ledger account not found: " + accountId);
        }

        // 7. Signed 64-bit BIGINT overflow guard
        if (reconstructedBalance.compareTo(BIGINT_MIN) < 0 || reconstructedBalance.compareTo(BIGINT_MAX) > 0) {
            log.error("Reconstructed balance {} for account {} exceeds signed 64-bit integer range", reconstructedBalance, accountId);
            throw new ReconciliationConflictException("Reconstructed balance for account " + accountId
                    + " exceeds signed 64-bit integer range (" + reconstructedBalance.toPlainString() + "); repair aborted");
        }

        long targetBalanceMinor = reconstructedBalance.longValueExact();
        ReconciliationResolutionAction action;

        // 8. Compare current snapshot to reconstructed truth
        if (targetBalanceMinor != currentBalanceMinor) {
            int updated = jdbcTemplate.update(UPDATE_SNAPSHOT_SQL, targetBalanceMinor, Timestamp.from(projectionUpdatedAt), accountId);
            if (updated != 1) {
                throw new IllegalStateException("Failed to update snapshot row for ledger account " + accountId);
            }
            reconCase.resolveSnapshotRepaired(actorUserId);
            action = ReconciliationResolutionAction.SNAPSHOT_REPAIRED;
            log.warn("Snapshot repaired for account {}: {} -> {} (run case={})", accountId, currentBalanceMinor, targetBalanceMinor, caseId);
        } else {
            // Already consistent; skip balance write
            reconCase.resolveAlreadyConsistent(actorUserId);
            action = ReconciliationResolutionAction.ALREADY_CONSISTENT;
            log.info("Snapshot already consistent for account {} at balance {} (run case={})", accountId, targetBalanceMinor, caseId);
        }

        // 9. Verify snapshot equals reconstructed value
        Long verifiedBalance = jdbcTemplate.queryForObject(
                "SELECT balance_minor FROM ledger_balance_snapshots WHERE ledger_account_id = ?",
                Long.class, accountId);
        if (verifiedBalance == null || verifiedBalance != targetBalanceMinor) {
            throw new IllegalStateException("Snapshot balance verification failed after repair for account " + accountId);
        }

        // 10. Persist case resolution
        caseRepository.saveAndFlush(reconCase);

        return new SnapshotRepairResponse(
                caseId,
                accountId,
                String.valueOf(currentBalanceMinor),
                String.valueOf(targetBalanceMinor),
                action.name(),
                projectionUpdatedAt
        );
    }

    private SnapshotRepairResponse buildExistingRepairResponse(ReconciliationCase reconCase, ReconciliationItem item) {
        UUID accountId = item.getEntityId();
        Long currentBal = null;
        Instant updated = null;
        try {
            var row = jdbcTemplate.queryForMap("SELECT balance_minor, updated_at FROM ledger_balance_snapshots WHERE ledger_account_id = ?", accountId);
            currentBal = ((Number) row.get("balance_minor")).longValue();
            updated = ((Timestamp) row.get("updated_at")).toInstant();
        } catch (Exception ignored) {
        }

        String balStr = currentBal != null ? String.valueOf(currentBal) : null;
        return new SnapshotRepairResponse(
                reconCase.getId(),
                accountId,
                balStr,
                balStr,
                reconCase.getResolutionAction().name(),
                updated != null ? updated : reconCase.getResolvedAt()
        );
    }
}
