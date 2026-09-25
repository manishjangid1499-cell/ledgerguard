import { apiClient } from '../shared/api/apiClient';
import { ApiError } from '../shared/types/api.types';
import { Funding, Payout, Payment, PaymentDetail, Refund, Page, Posted, PaymentRequest, MerchantSummary, PaymentFilters } from './types';

async function post<T extends object>(path: string, payload: object, key: string, idField: keyof T): Promise<T> {
  const result = await apiClient<T>(path, {
    method: 'POST', body: JSON.stringify(payload), headers: { 'Idempotency-Key': key },
  });
  // An unreadable success response is uncertain, never permission to submit a new intent.
  if (!result || typeof result[idField] !== 'string') {
    throw new ApiError({ status: 0, detail: 'The operation result could not be confirmed.' });
  }
  return result;
}

export const financialApi = {
  funding: (page = 0) => apiClient<Page<Funding>>(`/api/funding?page=${page}&size=10`),
  fundingDetail: (id: string) => apiClient<Funding>(`/api/funding/${id}`),
  payouts: (page = 0) => apiClient<Page<Payout>>(`/api/payouts?page=${page}&size=10`),
  payoutDetail: (id: string) => apiClient<Payout>(`/api/payouts/${id}`),
  payments: (params: PaymentFilters | number = 0) => {
    if (typeof params === 'number') {
      return apiClient<Page<Payment>>(`/api/payments?page=${params}&size=10`);
    }
    const query = new URLSearchParams();
    query.set('page', String(params.page ?? 0));
    query.set('size', String(params.size ?? 10));
    if (params.paymentId?.trim()) query.set('paymentId', params.paymentId.trim());
    if (params.status) query.set('status', params.status);
    if (params.sort) query.set('sort', params.sort);
    return apiClient<Page<Payment>>(`/api/payments?${query.toString()}`);
  },
  merchantSummary: () => apiClient<MerchantSummary>('/api/payments/summary'),
  paymentDetail: (id: string, refundPage = 0) => apiClient<PaymentDetail>(`/api/payments/${id}?refundPage=${refundPage}&refundSize=10`),
  addMoney: (payload: { amountMinor: string }, key: string) => post<Posted<Funding>>('/api/funding', payload, key, 'fundingId'),
  withdraw: (payload: { amountMinor: string }, key: string) => post<Posted<Payout>>('/api/payouts', payload, key, 'payoutId'),
  payMerchant: (payload: PaymentRequest, key: string) => post<Posted<Payment>>('/api/payments', payload, key, 'paymentId'),
  refund: (id: string, payload: { amountMinor: number }, key: string) =>
    post<Posted<Refund> & { paymentId: string }>(`/api/payments/${id}/refund`, payload, key, 'refundId'),
};
