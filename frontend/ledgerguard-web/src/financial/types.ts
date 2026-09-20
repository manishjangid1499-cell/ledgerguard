export type ProviderStatus = 'CREATED' | 'PROCESSING' | 'UNKNOWN' | 'RECONCILIATION_REQUIRED' | 'SUCCEEDED' | 'FAILED';
export type PaymentStatus = 'CREATED' | 'PROCESSING' | 'SUCCEEDED' | 'FAILED';
export type ProviderDomain = 'funding' | 'payouts';
export type FinancialDomain = ProviderDomain | 'payments';
export interface Page<T> { items: T[]; page: number; size: number; totalElements: number; totalPages: number }
interface ProviderOperation {
  amountMinor: string; currency: string; status: ProviderStatus;
  providerOperationId: string | null; journalTransactionId: string | null;
  createdAt: string; completedAt: string | null;
}
export interface Funding extends ProviderOperation { fundingId: string }
export interface Payout extends ProviderOperation { payoutId: string; balanceHoldId: string }
export interface Payment {
  paymentId: string; customerLedgerAccountId: string; merchantLedgerAccountId: string;
  grossAmountMinor: string; feeAmountMinor: string; merchantNetAmountMinor: string;
  currency: string; status: PaymentStatus; journalTransactionId: string | null;
  createdAt: string; completedAt: string | null;
}
export interface Refund {
  refundId: string; refundAmountMinor: string; merchantDebitAmountMinor: string; feeDebitAmountMinor: string;
  currency: string; journalTransactionId: string; createdAt: string;
}
export interface PaymentDetail {
  payment: Payment; refundedAmountMinor: string; refundableAmountMinor: string; refunds: Page<Refund>;
}
export type Posted<T> = T & { replayed: boolean };
export interface PaymentRequest { merchantLedgerAccountId: string; amountMinor: number }
