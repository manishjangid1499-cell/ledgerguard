import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  fetchActiveRun,
  fetchEnvironment,
  fetchRunById,
  fetchRunHistory,
  fetchScenarios,
  startScenarioRun,
} from '../api/failureLabApi';
import { LabRunView, ScenarioId } from '../types/failureLab.types';

export function useLabEnvironment() {
  return useQuery({
    queryKey: ['lab-environment'],
    queryFn: fetchEnvironment,
    refetchInterval: 10000,
    retry: 1,
  });
}

export function useLabScenarios() {
  return useQuery({
    queryKey: ['lab-scenarios'],
    queryFn: fetchScenarios,
    staleTime: 60000,
    retry: 1,
  });
}

export function useLabActiveRun() {
  return useQuery({
    queryKey: ['lab-active-run'],
    queryFn: fetchActiveRun,
    refetchInterval: (query) => {
      return query.state.data ? 800 : 3000;
    },
    retry: false,
  });
}

export function useLabRun(runId: string | null) {
  return useQuery<LabRunView>({
    queryKey: ['lab-run', runId],
    queryFn: () => fetchRunById(runId!),
    enabled: !!runId,
    refetchInterval: (query) => {
      const status = query.state.data?.status;
      if (status === 'RUNNING' || status === 'PENDING') {
        return 800;
      }
      return false;
    },
  });
}

export function useLabHistory(limit = 20) {
  return useQuery({
    queryKey: ['lab-history', limit],
    queryFn: () => fetchRunHistory(limit),
    refetchInterval: 5000,
    retry: 1,
  });
}

export function useStartScenario() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (scenarioId: ScenarioId) => startScenarioRun(scenarioId),
    onSuccess: (newRun) => {
      queryClient.setQueryData(['lab-active-run'], newRun);
      queryClient.setQueryData(['lab-run', newRun.runId], newRun);
      queryClient.invalidateQueries({ queryKey: ['lab-history'] });
    },
  });
}
