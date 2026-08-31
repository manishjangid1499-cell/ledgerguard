export type TransferDirection = 'INCOMING' | 'OUTGOING';

export interface CreateTransferRequest {
  destinationLedgerAccountId: string;
  amountMinor: number;
}

export interface TransferResponse {
  transferId: string;
  sourceLedgerAccountId: string;
  destinationLedgerAccountId: string;
  amountMinor: string;
  currency: string;
  journalTransactionId: string;
  createdAt: string;
  replayed: boolean;
}

export interface TransferSummary {
  transferId: string;
  sourceLedgerAccountId: string;
  destinationLedgerAccountId: string;
  amountMinor: string;
  currency: string;
  journalTransactionId: string;
  createdAt: string;
  direction: TransferDirection;
}

export interface PagedTransfersResponse {
  items: TransferSummary[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface JournalEntryDetail {
  ledgerAccountId: string;
  direction: 'DEBIT' | 'CREDIT';
  amountMinor: string;
}

export interface JournalDetail {
  journalTransactionId: string;
  status: string;
  postedAt: string;
  entries: JournalEntryDetail[];
}

export interface TransferDetail {
  transferId: string;
  sourceLedgerAccountId: string;
  destinationLedgerAccountId: string;
  amountMinor: string;
  currency: string;
  journalTransactionId: string;
  createdAt: string;
  direction: TransferDirection;
  journal: JournalDetail;
}
