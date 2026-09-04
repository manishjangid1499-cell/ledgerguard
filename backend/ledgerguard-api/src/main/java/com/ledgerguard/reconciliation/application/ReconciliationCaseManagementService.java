package com.ledgerguard.reconciliation.application;

import com.ledgerguard.reconciliation.api.ReconciliationCaseResponse;
import com.ledgerguard.reconciliation.domain.ReconciliationCase;
import com.ledgerguard.reconciliation.domain.ReconciliationCaseStatus;
import com.ledgerguard.reconciliation.domain.ReconciliationConflictException;
import com.ledgerguard.reconciliation.domain.ReconciliationItem;
import com.ledgerguard.reconciliation.domain.ReconciliationNotFoundException;
import com.ledgerguard.reconciliation.domain.ReconciliationProblemType;
import com.ledgerguard.reconciliation.domain.ReconciliationResolutionAction;
import com.ledgerguard.reconciliation.domain.ReconciliationValidationException;
import com.ledgerguard.reconciliation.infrastructure.ReconciliationCaseRepository;
import com.ledgerguard.reconciliation.infrastructure.ReconciliationItemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

/**
 * Service managing workflow transitions for reconciliation cases.
 * Mutates ONLY the {@code reconciliation_cases} table; never touches financial or business state.
 */
@Service
public class ReconciliationCaseManagementService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationCaseManagementService.class);

    private final ReconciliationCaseRepository caseRepository;
    private final ReconciliationItemRepository itemRepository;
    private final ReconciliationCaseQueryService queryService;

    public ReconciliationCaseManagementService(ReconciliationCaseRepository caseRepository,
                                               ReconciliationItemRepository itemRepository,
                                               ReconciliationCaseQueryService queryService) {
        this.caseRepository = caseRepository;
        this.itemRepository = itemRepository;
        this.queryService = queryService;
    }

    /**
     * Claims an OPEN case for the given operator, transitioning it to IN_REVIEW.
     * Replay by the same operator is an idempotent success.
     */
    @Transactional
    public ReconciliationCaseResponse claimCase(UUID caseId, UUID actorUserId) {
        Objects.requireNonNull(caseId, "Case ID must not be null");
        Objects.requireNonNull(actorUserId, "Actor user ID must not be null");

        ReconciliationCase reconCase = caseRepository.findByIdForUpdate(caseId)
                .orElseThrow(() -> new ReconciliationNotFoundException("Reconciliation case not found: " + caseId));

        ReconciliationItem item = itemRepository.findById(reconCase.getReconciliationItemId())
                .orElseThrow(() -> new ReconciliationNotFoundException("Reconciliation item not found for case: " + caseId));

        if (reconCase.getStatus() == ReconciliationCaseStatus.RESOLVED) {
            throw new ReconciliationConflictException("Cannot claim terminal resolved case " + caseId);
        }

        if (reconCase.getStatus() == ReconciliationCaseStatus.IN_REVIEW) {
            if (actorUserId.equals(reconCase.getAssignedToUserId())) {
                log.info("Idempotent claim replay on case {} by actor {}", caseId, actorUserId);
                return queryService.mapToCaseResponse(reconCase, item);
            }
            throw new ReconciliationConflictException("Case " + caseId + " is already claimed by another operator");
        }

        reconCase.claim(actorUserId);
        reconCase = caseRepository.saveAndFlush(reconCase);
        log.info("Case {} claimed by operator {}", caseId, actorUserId);

        return queryService.mapToCaseResponse(reconCase, item);
    }

    /**
     * Manually resolves a reconciliation case with an investigation note.
     * SNAPSHOT_MISMATCH cases cannot be manually closed (they must be auto-repaired).
     */
    @Transactional
    public ReconciliationCaseResponse resolveManually(UUID caseId, UUID actorUserId, String note) {
        Objects.requireNonNull(caseId, "Case ID must not be null");
        Objects.requireNonNull(actorUserId, "Actor user ID must not be null");

        if (note == null || note.trim().isEmpty()) {
            throw new ReconciliationValidationException("Resolution note must not be blank for manual review");
        }
        String normalizedNote = note.trim();
        if (normalizedNote.length() > 1000) {
            throw new ReconciliationValidationException("Resolution note must not exceed 1000 characters");
        }

        ReconciliationCase reconCase = caseRepository.findByIdForUpdate(caseId)
                .orElseThrow(() -> new ReconciliationNotFoundException("Reconciliation case not found: " + caseId));

        ReconciliationItem item = itemRepository.findById(reconCase.getReconciliationItemId())
                .orElseThrow(() -> new ReconciliationNotFoundException("Reconciliation item not found for case: " + caseId));

        // Idempotent terminal replay check
        if (reconCase.getStatus() == ReconciliationCaseStatus.RESOLVED) {
            if (reconCase.getResolutionAction() == ReconciliationResolutionAction.MANUAL_REVIEW_COMPLETED
                    && Objects.equals(reconCase.getResolutionNote(), normalizedNote)) {
                log.info("Idempotent manual resolve replay on case {} by actor {}", caseId, actorUserId);
                return queryService.mapToCaseResponse(reconCase, item);
            }
            throw new ReconciliationConflictException("Case " + caseId + " is already resolved with conflicting terminal action or note");
        }

        // Ownership check if already claimed
        if (reconCase.getStatus() == ReconciliationCaseStatus.IN_REVIEW) {
            if (!actorUserId.equals(reconCase.getAssignedToUserId())) {
                throw new ReconciliationConflictException("Case " + caseId + " is claimed by another operator; cannot resolve");
            }
        }

        // SNAPSHOT_MISMATCH cannot be manually closed
        if (item.getProblemType() == ReconciliationProblemType.SNAPSHOT_MISMATCH) {
            throw new ReconciliationConflictException("Problem type SNAPSHOT_MISMATCH cannot be manually closed; it must be resolved via auto-repair");
        }

        reconCase.resolveManualReview(actorUserId, normalizedNote);
        reconCase = caseRepository.saveAndFlush(reconCase);
        log.info("Case {} manually resolved by operator {} with note", caseId, actorUserId);

        return queryService.mapToCaseResponse(reconCase, item);
    }
}
