package com.ledgerguard.reconciliation.api;

import com.ledgerguard.reconciliation.application.ReconciliationCaseManagementService;
import com.ledgerguard.reconciliation.application.ReconciliationCaseQueryService;
import com.ledgerguard.reconciliation.application.SnapshotAutoRepairService;
import com.ledgerguard.reconciliation.domain.ReconciliationCaseStatus;
import com.ledgerguard.reconciliation.domain.ReconciliationClassification;
import com.ledgerguard.reconciliation.domain.ReconciliationLevel;
import com.ledgerguard.reconciliation.domain.ReconciliationProblemType;
import com.ledgerguard.shared.api.PagedResponse;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Operations REST controller exposing reconciliation runs, detected discrepancy items,
 * operational review queues, claim assignment, snapshot auto-repair, and manual resolution.
 * <p>
 * Accessible strictly to users with the {@code OPS} role.
 */
@RestController
@RequestMapping(value = "/api/reconciliation", produces = MediaType.APPLICATION_JSON_VALUE)
@PreAuthorize("hasRole('OPS')")
public class ReconciliationController {

    private final ReconciliationCaseQueryService queryService;
    private final ReconciliationCaseManagementService managementService;
    private final SnapshotAutoRepairService autoRepairService;

    public ReconciliationController(ReconciliationCaseQueryService queryService,
                                    ReconciliationCaseManagementService managementService,
                                    SnapshotAutoRepairService autoRepairService) {
        this.queryService = queryService;
        this.managementService = managementService;
        this.autoRepairService = autoRepairService;
    }

    @GetMapping("/runs")
    public ResponseEntity<PagedResponse<ReconciliationRunSummaryResponse>> getRuns(
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size
    ) {
        return ResponseEntity.ok(queryService.findRuns(page, size));
    }

    @GetMapping("/runs/{runId}")
    public ResponseEntity<ReconciliationRunSummaryResponse> getRunById(@PathVariable("runId") UUID runId) {
        return ResponseEntity.ok(queryService.findRunById(runId));
    }

    @GetMapping("/runs/{runId}/items")
    public ResponseEntity<PagedResponse<ReconciliationItemResponse>> getRunItems(
            @PathVariable("runId") UUID runId,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size
    ) {
        return ResponseEntity.ok(queryService.findItemsByRunId(runId, page, size));
    }

    @GetMapping("/cases")
    public ResponseEntity<PagedResponse<ReconciliationCaseResponse>> getCases(
            @RequestParam(value = "status", required = false) ReconciliationCaseStatus status,
            @RequestParam(value = "level", required = false) ReconciliationLevel level,
            @RequestParam(value = "classification", required = false) ReconciliationClassification classification,
            @RequestParam(value = "problemType", required = false) ReconciliationProblemType problemType,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size
    ) {
        return ResponseEntity.ok(queryService.findCases(status, level, classification, problemType, page, size));
    }

    @GetMapping("/cases/{caseId}")
    public ResponseEntity<ReconciliationCaseResponse> getCaseById(@PathVariable("caseId") UUID caseId) {
        return ResponseEntity.ok(queryService.findCaseById(caseId));
    }

    @PostMapping("/cases/{caseId}/claim")
    public ResponseEntity<ReconciliationCaseResponse> claimCase(
            @PathVariable("caseId") UUID caseId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        UUID actorUserId = UUID.fromString(jwt.getSubject());
        return ResponseEntity.ok(managementService.claimCase(caseId, actorUserId));
    }

    @PostMapping("/cases/{caseId}/repair-snapshot")
    public ResponseEntity<SnapshotRepairResponse> repairSnapshot(
            @PathVariable("caseId") UUID caseId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        UUID actorUserId = UUID.fromString(jwt.getSubject());
        return ResponseEntity.ok(autoRepairService.repairSnapshot(caseId, actorUserId));
    }

    @PostMapping("/cases/{caseId}/resolve")
    public ResponseEntity<ReconciliationCaseResponse> resolveCase(
            @PathVariable("caseId") UUID caseId,
            @Valid @RequestBody ManualResolveRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        UUID actorUserId = UUID.fromString(jwt.getSubject());
        return ResponseEntity.ok(managementService.resolveManually(caseId, actorUserId, request.resolutionNote()));
    }
}
