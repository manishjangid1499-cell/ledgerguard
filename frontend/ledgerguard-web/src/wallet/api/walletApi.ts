import { apiClient } from '../../shared/api/apiClient';
import { WalletResponse } from '../types/wallet.types';

export const walletApi = {
  getMyWallet(): Promise<WalletResponse> {
    return apiClient<WalletResponse>('/api/wallets/me');
  },
};
