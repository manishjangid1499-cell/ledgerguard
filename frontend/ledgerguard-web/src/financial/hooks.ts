import { useEffect, useRef } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { financialApi } from './api';
import { isUnconfirmed, needsConfirmation } from './feedback';
import { FinancialDomain, ProviderDomain, Funding, Payout } from './types';

export function useProviderOperation(domain: ProviderDomain, id: string) {
  const queryClient = useQueryClient();
  const query = useQuery<Funding | Payout>({
    queryKey: [domain, 'detail', id],
    queryFn: () => domain === 'funding' ? financialApi.fundingDetail(id) : financialApi.payoutDetail(id),
    enabled: Boolean(id), staleTime: 0,
    refetchInterval: query => !query.state.error && needsConfirmation(query.state.data?.status) ? 4000 : false,
    refetchIntervalInBackground: false,
  });
  const status = query.data?.status;
  useEffect(() => {
    if (!status) return;
    void queryClient.invalidateQueries({ queryKey: ['wallet'] });
    void queryClient.invalidateQueries({ queryKey: [domain, 'list'] });
  }, [domain, id, status, queryClient]);
  return query;
}

// Match the transfer pattern: one in-memory key bound to the exact payload,
// no automatic financial retries, and an immediate double-submit guard.
export function useFinancialSubmission<P extends object, R>(
  domain: FinancialDomain, submit: (payload: P, key: string) => Promise<R>, onSuccess: (result: R) => void,
) {
  const queryClient = useQueryClient();
  const intent = useRef<{ key: string; payload: P; fingerprint: string } | null>(null);
  const busy = useRef(false);
  const mutation = useMutation({
    mutationFn: ({ payload, key }: { payload: P; key: string }) => submit(payload, key),
    retry: false, onSuccess,
    onSettled: () => {
      busy.current = false;
      void queryClient.invalidateQueries({ queryKey: ['wallet'] });
      void queryClient.invalidateQueries({ queryKey: [domain] });
    },
  });
  const uncertain = mutation.isError && isUnconfirmed(mutation.error);
  const execute = (payload: P) => {
    if (busy.current || mutation.isSuccess) return;
    const fingerprint = JSON.stringify(payload);
    if (!intent.current || (!uncertain && intent.current.fingerprint !== fingerprint)) {
      intent.current = { key: crypto.randomUUID(), payload, fingerprint };
    }
    busy.current = true;
    mutation.mutate(intent.current);
  };
  const resetFeedback = () => { if (!busy.current && !uncertain) mutation.reset(); };
  return { ...mutation, uncertain, execute, resetFeedback };
}
