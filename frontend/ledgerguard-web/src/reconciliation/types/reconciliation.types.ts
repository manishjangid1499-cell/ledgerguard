export type ReconciliationRunStatus = 'RUNNING' | 'COMPLETED' | 'FAILED';

export type ReconciliationTrigger = 'SCHEDULED' | 'ON_DEMAND';

export type ReconciliationClassification = 'DISCREPANCY' | 'UNRESOLVED';

export type ReconciliationLevel =
  | 'JOURNAL_BALANCE'
  | 'SNAPSHOT_CONSISTENCY'
  | 'PROVIDER_SETTLEMENT';

export type ReconciliationProblemType =
  | 'UNBALANCED_JOURNAL'
  | 'MALFORMED_JOURNAL'
  | 'SNAPSHOT_MISMATCH'
  | 'SNAPSHOT_MISSING'
  | 'PROVIDER_STATUS_MISMATCH'
  | 'PROVIDER_IDENTITY_MISMATCH'
  | 'PROVIDER_NOT_FOUND'
  | 'PROVIDER_UNAVAILABLE'
  | 'PROVIDER_STILL_PROCESSING';

export type ReconciliationCaseStatus = 'OPEN' | 'IN_REVIEW' | 'RESOLVED';

export type ReconciliationResolutionAction =
  | 'SNAPSHOT_REPAIRED'
  | 'ALREADY_CONSISTENT'
  | 'MANUAL_REVIEW_COMPLETED';

export interface PagedResponse<T> {
  items: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface ReconciliationRunSummaryResponse {
  id: string;
  status: ReconciliationRunStatus;
  triggerSource: ReconciliationTrigger;
  startedAt: string;
  completedAt: string | null;
  journalsChecked: number;
  accountsChecked: number;
  operationsChecked: number;
  discrepancyCount: number;
  unresolvedCount: number;
  failureReason: string | null;
}

export interface ReconciliationItemResponse {
  id: string;
  reconciliationRunId: string;
  classification: ReconciliationClassification;
  level: ReconciliationLevel;
  problemType: ReconciliationProblemType;
  entityType: string;
  entityId: string;
  observedLocalStatus: string | null;
  expectedValue: number | null;
  actualValue: number | null;
  providerStatus: string | null;
  description: string;
  detectedAt: string;
}

export interface ReconciliationCaseResponse {
  id: string;
  reconciliationItemId: string;
  status: ReconciliationCaseStatus;
  assignedToUserId: string | null;
  resolvedByUserId: string | null;
  resolutionAction: ReconciliationResolutionAction | null;
  resolutionNote: string | null;
  openedAt: string;
  updatedAt: string;
  resolvedAt: string | null;
  item: ReconciliationItemResponse;
}

export interface ReconciliationDashboardSummaryResponse {
  openCases: number;
  inReviewCases: number;
  resolvedCases: number;
  totalCases: number;
  totalRuns: number;
  latestRun: ReconciliationRunSummaryResponse | null;
}

export interface SnapshotRepairResponse {
  caseId: string;
  ledgerAccountId: string;
  previousBalanceMinor: string;
  repairedBalanceMinor: string;
  resolutionAction: string;
  snapshotUpdatedAt: string;
  // Legacy compatibility fields if referenced by previous fixtures
  accountId?: string;
  repaired?: boolean;
  oldBalanceMinor?: number;
  newBalanceMinor?: number;
  journalEntriesCount?: number;
  message?: string;
}

export interface ManualResolveRequest {
  resolutionNote: string;
}

export interface CaseFilterParams {
  status?: ReconciliationCaseStatus;
  level?: ReconciliationLevel;
  classification?: ReconciliationClassification;
  problemType?: ReconciliationProblemType;
  runId?: string;
  assigned?: boolean;
  page?: number;
  size?: number;
}
