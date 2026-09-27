import { apiClient } from '../../shared/api/apiClient';
import {
  CaseFilterParams,
  ManualResolveRequest,
  PagedResponse,
  ReconciliationCaseResponse,
  ReconciliationDashboardSummaryResponse,
  ReconciliationItemResponse,
  ReconciliationRunSummaryResponse,
  SnapshotRepairResponse,
} from '../types/reconciliation.types';

export const reconciliationApi = {
  getSummary(): Promise<ReconciliationDashboardSummaryResponse> {
    return apiClient<ReconciliationDashboardSummaryResponse>('/api/reconciliation/summary');
  },

  triggerRun(): Promise<ReconciliationRunSummaryResponse> {
    return apiClient<ReconciliationRunSummaryResponse>('/api/reconciliation/runs', {
      method: 'POST',
    });
  },

  getRuns(page = 0, size = 20): Promise<PagedResponse<ReconciliationRunSummaryResponse>> {
    return apiClient<PagedResponse<ReconciliationRunSummaryResponse>>(
      `/api/reconciliation/runs?page=${page}&size=${size}`
    );
  },

  getRunById(runId: string): Promise<ReconciliationRunSummaryResponse> {
    return apiClient<ReconciliationRunSummaryResponse>(`/api/reconciliation/runs/${runId}`);
  },

  getRunItems(
    runId: string,
    page = 0,
    size = 20
  ): Promise<PagedResponse<ReconciliationItemResponse>> {
    return apiClient<PagedResponse<ReconciliationItemResponse>>(
      `/api/reconciliation/runs/${runId}/items?page=${page}&size=${size}`
    );
  },

  getCases(filters: CaseFilterParams = {}): Promise<PagedResponse<ReconciliationCaseResponse>> {
    const params = new URLSearchParams();
    if (filters.page !== undefined) params.append('page', String(filters.page));
    if (filters.size !== undefined) params.append('size', String(filters.size));
    if (filters.status) params.append('status', filters.status);
    if (filters.level) params.append('level', filters.level);
    if (filters.classification) params.append('classification', filters.classification);
    if (filters.problemType) params.append('problemType', filters.problemType);
    if (filters.runId) params.append('runId', filters.runId);
    if (filters.assigned !== undefined) params.append('assigned', String(filters.assigned));

    const queryString = params.toString();
    const url = queryString ? `/api/reconciliation/cases?${queryString}` : '/api/reconciliation/cases';
    return apiClient<PagedResponse<ReconciliationCaseResponse>>(url);
  },

  getCaseById(caseId: string): Promise<ReconciliationCaseResponse> {
    return apiClient<ReconciliationCaseResponse>(`/api/reconciliation/cases/${caseId}`);
  },

  claimCase(caseId: string): Promise<ReconciliationCaseResponse> {
    return apiClient<ReconciliationCaseResponse>(`/api/reconciliation/cases/${caseId}/claim`, {
      method: 'POST',
    });
  },

  resolveCase(
    caseId: string,
    request: ManualResolveRequest
  ): Promise<ReconciliationCaseResponse> {
    return apiClient<ReconciliationCaseResponse>(`/api/reconciliation/cases/${caseId}/resolve`, {
      method: 'POST',
      body: JSON.stringify(request),
    });
  },

  repairSnapshot(caseId: string): Promise<SnapshotRepairResponse> {
    return apiClient<SnapshotRepairResponse>(
      `/api/reconciliation/cases/${caseId}/repair-snapshot`,
      {
        method: 'POST',
      }
    );
  },
};
