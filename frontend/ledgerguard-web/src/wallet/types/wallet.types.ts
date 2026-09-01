export interface WalletResponse {
  ledgerAccountId: string;
  accountType: 'CUSTOMER' | 'MERCHANT';
  currency: string;
  status: 'ACTIVE' | 'FROZEN' | 'CLOSED';
  balanceMinor: string;
  activeHoldAmountMinor: string;
  availableBalanceMinor: string;
}
