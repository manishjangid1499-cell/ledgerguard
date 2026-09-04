package com.ledgerguard.reconciliation.application;

import com.ledgerguard.reconciliation.api.ReconciliationCaseResponse;
import com.ledgerguard.reconciliation.api.ReconciliationItemResponse;
import com.ledgerguard.reconciliation.api.ReconciliationRunSummaryResponse;
import com.ledgerguard.reconciliation.domain.ReconciliationCase;
import com.ledgerguard.reconciliation.domain.ReconciliationCaseStatus;
import com.ledgerguard.reconciliation.domain.ReconciliationClassification;
import com.ledgerguard.reconciliation.domain.ReconciliationItem;
import com.ledgerguard.reconciliation.domain.ReconciliationLevel;
import com.ledgerguard.reconciliation.domain.ReconciliationNotFoundException;
import com.ledgerguard.reconciliation.domain.ReconciliationProblemType;
import com.ledgerguard.reconciliation.domain.ReconciliationRun;
import com.ledgerguard.reconciliation.domain.ReconciliationValidationException;
import com.ledgerguard.reconciliation.infrastructure.ReconciliationCaseRepository;
import com.ledgerguard.reconciliation.infrastructure.ReconciliationItemRepository;
import com.ledgerguard.reconciliation.infrastructure.ReconciliationRunRepository;
import com.ledgerguard.shared.api.PagedResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Read-only query service for reconciliation runs, detected discrepancy items,
 * and operational manual review cases.
 */
@Service
@Transactional(readOnly = true)
public class ReconciliationCaseQueryService {

    public static final int DEFAULT_PAGE = 0;
    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    private final ReconciliationRunRepository runRepository;
    private final ReconciliationItemRepository itemRepository;
    private final ReconciliationCaseRepository caseRepository;

    public ReconciliationCaseQueryService(ReconciliationRunRepository runRepository,
                                          ReconciliationItemRepository itemRepository,
                                          ReconciliationCaseRepository caseRepository) {
        this.runRepository = runRepository;
        this.itemRepository = itemRepository;
        this.caseRepository = caseRepository;
    }

    public PagedResponse<ReconciliationRunSummaryResponse> findRuns(Integer page, Integer size) {
        PageRequest pageRequest = validateAndBuildPageRequest(page, size, Sort.by(Sort.Order.desc("startedAt"), Sort.Order.desc("id")));
        Page<ReconciliationRun> runPage = runRepository.findAll(pageRequest);

        List<ReconciliationRunSummaryResponse> items = runPage.getContent().stream()
                .map(this::mapToRunSummary)
                .toList();

        return new PagedResponse<>(items, runPage.getNumber(), runPage.getSize(), runPage.getTotalElements(), runPage.getTotalPages());
    }

    public ReconciliationRunSummaryResponse findRunById(UUID runId) {
        ReconciliationRun run = runRepository.findById(runId)
                .orElseThrow(() -> new ReconciliationNotFoundException("Reconciliation run not found: " + runId));
        return mapToRunSummary(run);
    }

    public PagedResponse<ReconciliationItemResponse> findItemsByRunId(UUID runId, Integer page, Integer size) {
        if (!runRepository.existsById(runId)) {
            throw new ReconciliationNotFoundException("Reconciliation run not found: " + runId);
        }
        PageRequest pageRequest = validateAndBuildPageRequest(page, size, Sort.by(Sort.Order.desc("detectedAt"), Sort.Order.desc("id")));
        Page<ReconciliationItem> itemPage = itemRepository.findByReconciliationRunId(runId, pageRequest);

        List<ReconciliationItemResponse> items = itemPage.getContent().stream()
                .map(this::mapToItemResponse)
                .toList();

        return new PagedResponse<>(items, itemPage.getNumber(), itemPage.getSize(), itemPage.getTotalElements(), itemPage.getTotalPages());
    }

    public PagedResponse<ReconciliationCaseResponse> findCases(ReconciliationCaseStatus status,
                                                               ReconciliationLevel level,
                                                               ReconciliationClassification classification,
                                                               ReconciliationProblemType problemType,
                                                               Integer page,
                                                               Integer size) {
        PageRequest pageRequest = validateAndBuildPageRequest(page, size, Sort.by(Sort.Order.desc("openedAt"), Sort.Order.desc("id")));
        Page<Object[]> pageResult = caseRepository.findCasesWithItemFiltered(status, level, classification, problemType, pageRequest);

        List<ReconciliationCaseResponse> items = pageResult.getContent().stream()
                .map(row -> {
                    ReconciliationCase c = (ReconciliationCase) row[0];
                    ReconciliationItem i = (ReconciliationItem) row[1];
                    return mapToCaseResponse(c, i);
                })
                .toList();

        return new PagedResponse<>(items, pageResult.getNumber(), pageResult.getSize(), pageResult.getTotalElements(), pageResult.getTotalPages());
    }

    public ReconciliationCaseResponse findCaseById(UUID caseId) {
        ReconciliationCase c = caseRepository.findById(caseId)
                .orElseThrow(() -> new ReconciliationNotFoundException("Reconciliation case not found: " + caseId));
        ReconciliationItem i = itemRepository.findById(c.getReconciliationItemId())
                .orElseThrow(() -> new ReconciliationNotFoundException("Reconciliation item not found for case: " + caseId));
        return mapToCaseResponse(c, i);
    }

    public PageRequest validateAndBuildPageRequest(Integer page, Integer size, Sort sort) {
        int resolvedPage = (page != null) ? page : DEFAULT_PAGE;
        int resolvedSize = (size != null) ? size : DEFAULT_SIZE;

        if (resolvedPage < 0) {
            throw new ReconciliationValidationException("Page index must not be negative: " + resolvedPage);
        }
        if (resolvedSize < 1) {
            throw new ReconciliationValidationException("Page size must be at least 1: " + resolvedSize);
        }
        if (resolvedSize > MAX_SIZE) {
            throw new ReconciliationValidationException("Page size must not exceed " + MAX_SIZE + ": " + resolvedSize);
        }

        return PageRequest.of(resolvedPage, resolvedSize, sort);
    }

    private ReconciliationRunSummaryResponse mapToRunSummary(ReconciliationRun run) {
        return new ReconciliationRunSummaryResponse(
                run.getId(),
                run.getStatus().name(),
                run.getTriggerSource().name(),
                run.getStartedAt(),
                run.getCompletedAt(),
                run.getJournalsChecked(),
                run.getAccountsChecked(),
                run.getOperationsChecked(),
                run.getDiscrepancyCount(),
                run.getUnresolvedCount(),
                run.getFailureReason()
        );
    }

    public ReconciliationItemResponse mapToItemResponse(ReconciliationItem item) {
        return new ReconciliationItemResponse(
                item.getId(),
                item.getReconciliationRunId(),
                item.getClassification().name(),
                item.getLevel().name(),
                item.getProblemType().name(),
                item.getEntityType(),
                item.getEntityId(),
                item.getObservedLocalStatus(),
                formatNumeric(item.getExpectedValue()),
                formatNumeric(item.getActualValue()),
                item.getProviderStatus(),
                item.getDescription(),
                item.getDetectedAt()
        );
    }

    public ReconciliationCaseResponse mapToCaseResponse(ReconciliationCase c, ReconciliationItem i) {
        return new ReconciliationCaseResponse(
                c.getId(),
                c.getReconciliationItemId(),
                c.getStatus().name(),
                c.getAssignedToUserId(),
                c.getResolvedByUserId(),
                c.getResolutionAction() != null ? c.getResolutionAction().name() : null,
                c.getResolutionNote(),
                c.getOpenedAt(),
                c.getUpdatedAt(),
                c.getResolvedAt(),
                i != null ? mapToItemResponse(i) : null
        );
    }

    private String formatNumeric(BigDecimal value) {
        return value != null ? value.toPlainString() : null;
    }
}
