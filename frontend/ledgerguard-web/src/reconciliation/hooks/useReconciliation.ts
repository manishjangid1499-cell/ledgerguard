import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { reconciliationApi } from '../api/reconciliationApi';
import {
  CaseFilterParams,
  ManualResolveRequest,
  ReconciliationCaseResponse,
  ReconciliationDashboardSummaryResponse,
  ReconciliationItemResponse,
  ReconciliationRunSummaryResponse,
  SnapshotRepairResponse,
  PagedResponse,
} from '../types/reconciliation.types';

export const RECONCILIATION_QUERY_KEYS = {
  summary: ['reconciliation', 'summary'] as const,
  runs: (page = 0, size = 20) => ['reconciliation', 'runs', { page, size }] as const,
  run: (runId: string) => ['reconciliation', 'run', runId] as const,
  runItems: (runId: string, page = 0, size = 20) =>
    ['reconciliation', 'run-items', runId, { page, size }] as const,
  cases: (filters: CaseFilterParams = {}) => ['reconciliation', 'cases', filters] as const,
  case: (caseId: string) => ['reconciliation', 'case', caseId] as const,
};

export const useReconciliationSummary = () => {
  return useQuery<ReconciliationDashboardSummaryResponse>({
    queryKey: RECONCILIATION_QUERY_KEYS.summary,
    queryFn: () => reconciliationApi.getSummary(),
    refetchOnWindowFocus: true,
  });
};

export const useReconciliationRuns = (page = 0, size = 20) => {
  return useQuery<PagedResponse<ReconciliationRunSummaryResponse>>({
    queryKey: RECONCILIATION_QUERY_KEYS.runs(page, size),
    queryFn: () => reconciliationApi.getRuns(page, size),
  });
};

export const useReconciliationRun = (runId: string) => {
  return useQuery<ReconciliationRunSummaryResponse>({
    queryKey: RECONCILIATION_QUERY_KEYS.run(runId),
    queryFn: () => reconciliationApi.getRunById(runId),
    enabled: Boolean(runId),
  });
};

export const useReconciliationRunItems = (runId: string, page = 0, size = 20) => {
  return useQuery<PagedResponse<ReconciliationItemResponse>>({
    queryKey: RECONCILIATION_QUERY_KEYS.runItems(runId, page, size),
    queryFn: () => reconciliationApi.getRunItems(runId, page, size),
    enabled: Boolean(runId),
  });
};

export const useReconciliationCases = (filters: CaseFilterParams = {}) => {
  return useQuery<PagedResponse<ReconciliationCaseResponse>>({
    queryKey: RECONCILIATION_QUERY_KEYS.cases(filters),
    queryFn: () => reconciliationApi.getCases(filters),
  });
};

export const useReconciliationCase = (caseId: string) => {
  return useQuery<ReconciliationCaseResponse>({
    queryKey: RECONCILIATION_QUERY_KEYS.case(caseId),
    queryFn: () => reconciliationApi.getCaseById(caseId),
    enabled: Boolean(caseId),
  });
};

export const useTriggerReconciliationRun = () => {
  const queryClient = useQueryClient();

  return useMutation<ReconciliationRunSummaryResponse, Error, void>({
    mutationFn: () => reconciliationApi.triggerRun(),
    onSuccess: () => {
      // Invalidate queries so UI reflects authoritative server state
      queryClient.invalidateQueries({ queryKey: ['reconciliation'] });
    },
  });
};

export const useClaimCase = () => {
  const queryClient = useQueryClient();

  return useMutation<ReconciliationCaseResponse, Error, string>({
    mutationFn: (caseId: string) => reconciliationApi.claimCase(caseId),
    onSuccess: (_, caseId) => {
      queryClient.invalidateQueries({ queryKey: RECONCILIATION_QUERY_KEYS.summary });
      queryClient.invalidateQueries({ queryKey: ['reconciliation', 'cases'] });
      queryClient.invalidateQueries({ queryKey: RECONCILIATION_QUERY_KEYS.case(caseId) });
    },
  });
};

export const useResolveCase = () => {
  const queryClient = useQueryClient();

  return useMutation<ReconciliationCaseResponse, Error, { caseId: string; request: ManualResolveRequest }>({
    mutationFn: ({ caseId, request }) => reconciliationApi.resolveCase(caseId, request),
    onSuccess: (_, { caseId }) => {
      queryClient.invalidateQueries({ queryKey: RECONCILIATION_QUERY_KEYS.summary });
      queryClient.invalidateQueries({ queryKey: ['reconciliation', 'cases'] });
      queryClient.invalidateQueries({ queryKey: RECONCILIATION_QUERY_KEYS.case(caseId) });
    },
  });
};

export const useRepairSnapshot = () => {
  const queryClient = useQueryClient();

  return useMutation<SnapshotRepairResponse, Error, string>({
    mutationFn: (caseId: string) => reconciliationApi.repairSnapshot(caseId),
    onSuccess: (_, caseId) => {
      queryClient.invalidateQueries({ queryKey: RECONCILIATION_QUERY_KEYS.summary });
      queryClient.invalidateQueries({ queryKey: ['reconciliation', 'cases'] });
      queryClient.invalidateQueries({ queryKey: RECONCILIATION_QUERY_KEYS.case(caseId) });
    },
  });
};
