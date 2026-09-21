import { ApiError } from '../types/api.types';

// Present known API errors without exposing exception text or internal identifiers.
const messages: Record<string, string> = {
  INVALID_CREDENTIALS: 'The email or password is incorrect.',
  EMAIL_ALREADY_REGISTERED: 'An account with this email already exists. Sign in to continue.',
  VALIDATION_FAILED: 'Review the highlighted fields and try again.',
  INSUFFICIENT_FUNDS: 'Your available balance is too low for this transfer.',
  INVALID_TRANSFER: 'This transfer cannot be made. Check the recipient wallet and amount.',
  MERCHANT_PAYMENT_REQUIRED: 'This wallet belongs to a Merchant. Use Pay merchant instead.',
  IDEMPOTENCY_CONFLICT: 'This request conflicts with an earlier transfer. Check your transfer history before continuing.',
  IDEMPOTENCY_OPERATION_IN_PROGRESS: 'This transfer is still being processed. Check your transfer history for confirmation.',
  RATE_LIMIT_EXCEEDED: 'Too many requests. Wait a moment before trying again.',
};

export function getErrorMessage(error: unknown, fallback: string): string {
  if (!(error instanceof ApiError)) return fallback;
  if (error.status === 0) return 'Unable to connect. Check your connection and try again.';
  if (error.status >= 500) return fallback;
  const knownMessage = messages[error.problem.errorCode || ''];
  if (knownMessage) return knownMessage;
  if (error.status === 401) return 'Your session could not be verified. Sign in again to continue.';
  if (error.status === 403) return 'You do not have permission to access this information.';
  if (error.status === 429) return messages.RATE_LIMIT_EXCEEDED;
  return fallback;
}
