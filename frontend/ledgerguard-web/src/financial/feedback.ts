import { ApiError } from '../shared/types/api.types';
import { getErrorMessage } from '../shared/api/errorMessage';
import { ProviderDomain, ProviderStatus } from './types';

export function isUnconfirmed(error: unknown): boolean {
  return !(error instanceof ApiError) || error.status === 0 || error.status >= 500
    || error.problem.errorCode === 'IDEMPOTENCY_OPERATION_IN_PROGRESS'
    || error.problem.errorCode === 'PROVIDER_EVENT_CONFLICT';
}
export function financialError(error: unknown, fallback = 'Unable to load this financial record. Please try again.'): string {
  if (error instanceof ApiError) {
    const messages: Record<string, string> = {
      INSUFFICIENT_FUNDS: 'Your available balance is not sufficient for this operation.',
      RESOURCE_NOT_FOUND: 'The requested financial record or wallet could not be found.',
      REFUND_LIMIT_EXCEEDED: 'This amount exceeds the remaining refundable amount. Refresh the payment and review its refunds.',
      PAYMENT_NOT_REFUNDABLE: 'This payment is not eligible for a refund.',
      INVALID_PAYMENT: 'Check the Merchant wallet ID and amount. The recipient must have an active Merchant wallet.',
      INVALID_FUNDING: 'Check the amount and confirm that your Customer wallet is active.',
      INVALID_PAYOUT: 'Check the amount and confirm that your wallet is active.',
      IDEMPOTENCY_CONFLICT: 'This request conflicts with an earlier operation. Review your activity before starting another request.',
      IDEMPOTENCY_OPERATION_IN_PROGRESS: 'Your request is still being processed. Retry the same request to check its result.',
    };
    if (error.status === 404) return messages.RESOURCE_NOT_FOUND;
    if (error.status < 500 && messages[error.problem.errorCode || '']) return messages[error.problem.errorCode || ''];
  }
  return getErrorMessage(error, fallback);
}
export function needsConfirmation(status?: ProviderStatus): boolean {
  return status === 'CREATED' || status === 'PROCESSING' || status === 'UNKNOWN' || status === 'RECONCILIATION_REQUIRED';
}
export function providerMessage(domain: ProviderDomain, status: ProviderStatus): string {
  if (status === 'SUCCEEDED') return domain === 'funding'
    ? 'Funding is complete. Your wallet balance reflects confirmed settlement.'
    : 'Your payout is complete. The reserved funds have been settled.';
  if (status === 'FAILED') return domain === 'funding'
    ? 'This funding request failed. No funds were credited by this operation.'
    : 'This payout failed. Check your refreshed wallet for the available balance.';
  const state = status === 'UNKNOWN' ? 'Provider confirmation is pending. This is not a confirmed failure.'
    : status === 'RECONCILIATION_REQUIRED' ? 'This operation is pending review before its outcome can be confirmed.'
    : 'Your request is recorded and processing. Settlement is not confirmed yet.';
  return state + (domain === 'payouts'
    ? ' Funds may remain on hold until the outcome is resolved.'
    : ' Your balance changes only after confirmed settlement.');
}
