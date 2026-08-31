import { apiClient } from '../../shared/api/apiClient';
import {
  CreateTransferRequest,
  PagedTransfersResponse,
  TransferDetail,
  TransferResponse,
} from '../types/transfer.types';

export const transferApi = {
  getTransfers(page = 0, size = 20): Promise<PagedTransfersResponse> {
    return apiClient<PagedTransfersResponse>(`/api/transfers?page=${page}&size=${size}`);
  },

  getTransferDetail(transferId: string): Promise<TransferDetail> {
    return apiClient<TransferDetail>(`/api/transfers/${transferId}`);
  },

  createTransfer(request: CreateTransferRequest, idempotencyKey: string): Promise<TransferResponse> {
    return apiClient<TransferResponse>('/api/transfers', {
      method: 'POST',
      body: JSON.stringify(request),
      headers: {
        'Idempotency-Key': idempotencyKey,
      },
    });
  },
};
