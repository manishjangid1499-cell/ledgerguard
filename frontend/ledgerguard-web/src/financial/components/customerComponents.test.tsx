import { describe, it, expect, vi, beforeEach } from 'vitest';
import { screen, waitFor, fireEvent } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { renderWithProviders } from '../../test/test-utils';
import { CustomerDashboard } from './CustomerDashboard';
import { CustomerPaymentForm } from './CustomerPaymentForm';
import { CustomerFundingForm } from './CustomerFundingForm';
import { WithdrawalSection } from './WithdrawalSection';
import { TransferForm } from '../../transfer/components/TransferForm';
import { FinancialHistory } from './FinancialHistory';
import { ActivityPage } from '../pages/ActivityPage';
import { PaymentDetailPage } from '../pages/PaymentDetailPage';
import { TransferDetailPage } from '../../transfer/pages/TransferDetailPage';
import { walletApi } from '../../wallet/api/walletApi';
import { transferApi } from '../../transfer/api/transferApi';
import { financialApi } from '../api';
import { Payment, PaymentDetail } from '../types';
import { TransferDetail, TransferSummary } from '../../transfer/types/transfer.types';
import { Wallet } from '../../wallet/types';
import { UserSummary } from '../../shared/types/user.types';
import { ApiError } from '../../shared/types/api.types';

const mockCustomerUser: UserSummary = {
  id: '00000000-0000-0000-0000-000000000003',
  email: 'customer@example.com',
  role: 'CUSTOMER',
  status: 'ACTIVE',
  createdAt: '2026-09-24T00:00:00Z',
  fullName: 'Alice Customer',
};

const mockMerchantUser: UserSummary = {
  id: '00000000-0000-0000-0000-000000000001',
  email: 'merchant@example.com',
  role: 'MERCHANT',
  status: 'ACTIVE',
  createdAt: '2026-09-24T00:00:00Z',
  fullName: 'Acme Merchant',
};

const mockWallet: Wallet = {
  ledgerAccountId: '00000000-0000-0000-0000-000000000003',
  currency: 'INR',
  balanceMinor: '50000',
  availableBalanceMinor: '45000',
  activeHoldAmountMinor: '5000',
  updatedAt: '2026-09-24T00:00:00Z',
  status: 'ACTIVE',
};

const mockTransfers: TransferSummary[] = [
  {
    transferId: '99999999-9999-9999-9999-999999999999',
    sourceLedgerAccountId: mockWallet.ledgerAccountId,
    destinationLedgerAccountId: '00000000-0000-0000-0000-000000000004',
    amountMinor: '2500',
    currency: 'INR',
    direction: 'OUTGOING',
    createdAt: '2026-09-24T12:00:00Z',
  },
  {
    transferId: '88888888-8888-8888-8888-888888888888',
    sourceLedgerAccountId: '00000000-0000-0000-0000-000000000005',
    destinationLedgerAccountId: mockWallet.ledgerAccountId,
    amountMinor: '5000',
    currency: 'INR',
    direction: 'INCOMING',
    createdAt: '2026-09-24T11:00:00Z',
  },
];

const mockPayments: Payment[] = [
  {
    paymentId: '11111111-1111-1111-1111-111111111111',
    customerLedgerAccountId: mockWallet.ledgerAccountId,
    merchantLedgerAccountId: '00000000-0000-0000-0000-000000000002',
    grossAmountMinor: '10000',
    feeAmountMinor: '100',
    merchantNetAmountMinor: '9900',
    currency: 'INR',
    status: 'SUCCEEDED',
    journalTransactionId: '22222222-2222-2222-2222-222222222222',
    createdAt: '2026-09-24T12:00:00Z',
    completedAt: '2026-09-24T12:00:05Z',
    refundedAmountMinor: '0',
    refundStatus: 'NOT_REFUNDED',
  },
];

describe('Customer Experience Frontend Components', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    vi.spyOn(navigator.clipboard, 'writeText').mockResolvedValue();
    vi.spyOn(walletApi, 'getMyWallet').mockResolvedValue(mockWallet);
    vi.spyOn(transferApi, 'getTransfers').mockResolvedValue({
      items: mockTransfers,
      page: 0,
      size: 5,
      totalElements: 2,
      totalPages: 1,
    });
    vi.spyOn(financialApi, 'payments').mockResolvedValue({
      items: mockPayments,
      page: 0,
      size: 10,
      totalElements: 1,
      totalPages: 1,
    });
    vi.spyOn(financialApi, 'funding').mockResolvedValue({
      items: [],
      page: 0,
      size: 10,
      totalElements: 0,
      totalPages: 0,
    });
    vi.spyOn(financialApi, 'payouts').mockResolvedValue({
      items: [],
      page: 0,
      size: 10,
      totalElements: 0,
      totalPages: 0,
    });
  });

  describe('CustomerDashboard', () => {
    it('displays loading skeletons while initial queries are pending', () => {
      vi.spyOn(walletApi, 'getMyWallet').mockReturnValue(new Promise(() => {}));
      vi.spyOn(transferApi, 'getTransfers').mockReturnValue(new Promise(() => {}));

      const { container } = renderWithProviders(<CustomerDashboard />, { user: mockCustomerUser });
      const skeletons = container.querySelectorAll('.MuiSkeleton-root');
      expect(skeletons.length).toBeGreaterThan(0);
    });

    it('displays API error alert and provides a working Retry action', async () => {
      const user = userEvent.setup();
      const getWalletSpy = vi.spyOn(walletApi, 'getMyWallet')
        .mockRejectedValueOnce(new Error('Failed to load wallet'))
        .mockResolvedValueOnce(mockWallet);

      renderWithProviders(<CustomerDashboard />, { user: mockCustomerUser });

      const errorAlert = await screen.findByText(/Failed to load wallet|Unable to load wallet balances/i);
      expect(errorAlert).toBeInTheDocument();

      const retryButton = screen.getByRole('button', { name: /retry/i });
      await user.click(retryButton);

      await waitFor(() => {
        expect(getWalletSpy).toHaveBeenCalledTimes(2);
        expect(screen.getByText('₹450.00')).toBeInTheDocument();
      });
    });

    it('renders wallet summary balances and copyable wallet ID', async () => {
      renderWithProviders(<CustomerDashboard />, { user: mockCustomerUser });

      await waitFor(() => {
        expect(screen.getByText('₹450.00')).toBeInTheDocument();
        expect(screen.getByText('₹50.00')).toBeInTheDocument();
        expect(screen.getByText('₹500.00')).toBeInTheDocument();
      });

      expect(screen.getByText(mockWallet.ledgerAccountId)).toBeInTheDocument();
      expect(screen.getByLabelText(/copy wallet ID/i)).toBeInTheDocument();
    });

    it('renders all four equal quick actions linking to correct activity tabs with simulated provider copy', () => {
      renderWithProviders(<CustomerDashboard />, { user: mockCustomerUser });

      const addMoneyLink = screen.getByRole('link', { name: /add money/i });
      expect(addMoneyLink).toHaveAttribute('href', '/app/activity?type=funding');

      const sendMoneyLink = screen.getByRole('link', { name: /send money/i });
      expect(sendMoneyLink).toHaveAttribute('href', '/app/activity?type=transfers');

      const payMerchantLink = screen.getByRole('link', { name: /pay merchant/i });
      expect(payMerchantLink).toHaveAttribute('href', '/app/activity?type=payments');

      const withdrawLink = screen.getByRole('link', { name: /withdraw funds/i });
      expect(withdrawLink).toHaveAttribute('href', '/app/activity?type=withdrawals');
      expect(screen.getByText(/Move money through simulated payout provider/i)).toBeInTheDocument();
      expect(screen.queryByText(/external bank/i)).not.toBeInTheDocument();
    });

    it('disables quick actions and warns when wallet status is not ACTIVE', async () => {
      vi.spyOn(walletApi, 'getMyWallet').mockResolvedValue({
        ...mockWallet,
        status: 'SUSPENDED',
      });

      renderWithProviders(<CustomerDashboard />, { user: mockCustomerUser });

      await waitFor(() => {
        expect(screen.getByText(/Your wallet status is SUSPENDED. Financial actions/i)).toBeInTheDocument();
      });

      expect(screen.queryByRole('link', { name: /add money/i })).not.toBeInTheDocument();
      expect(screen.queryByRole('link', { name: /send money/i })).not.toBeInTheDocument();
      expect(screen.queryByRole('link', { name: /pay merchant/i })).not.toBeInTheDocument();
      expect(screen.queryByRole('link', { name: /withdraw funds/i })).not.toBeInTheDocument();

      const addMoneyBtn = screen.getByRole('button', { name: /add money/i });
      expect(addMoneyBtn).toBeDisabled();
      expect(addMoneyBtn).toHaveAttribute('aria-disabled', 'true');

      const sendMoneyBtn = screen.getByRole('button', { name: /send money/i });
      expect(sendMoneyBtn).toBeDisabled();
      expect(sendMoneyBtn).toHaveAttribute('aria-disabled', 'true');

      const payMerchantBtn = screen.getByRole('button', { name: /pay merchant/i });
      expect(payMerchantBtn).toBeDisabled();
      expect(payMerchantBtn).toHaveAttribute('aria-disabled', 'true');

      const withdrawBtn = screen.getByRole('button', { name: /withdraw funds/i });
      expect(withdrawBtn).toBeDisabled();
      expect(withdrawBtn).toHaveAttribute('aria-disabled', 'true');
    });

    it('prevents click and keyboard activation (Enter, Space) on inactive quick actions', async () => {
      const user = userEvent.setup();
      vi.spyOn(walletApi, 'getMyWallet').mockResolvedValue({
        ...mockWallet,
        status: 'FROZEN',
      });

      renderWithProviders(<CustomerDashboard />, { user: mockCustomerUser });

      await waitFor(() => {
        expect(screen.getByText(/Your wallet status is FROZEN/i)).toBeInTheDocument();
      });

      const addMoneyBtn = screen.getByRole('button', { name: /add money/i });
      expect(addMoneyBtn).toBeDisabled();

      // Attempt click
      await user.click(addMoneyBtn);

      // Attempt keyboard activation (Enter and Space)
      fireEvent.keyDown(addMoneyBtn, { key: 'Enter', code: 'Enter' });
      fireEvent.keyDown(addMoneyBtn, { key: ' ', code: 'Space' });

      // Verify no navigation occurred and no link exists
      expect(screen.queryByRole('link', { name: /add money/i })).not.toBeInTheDocument();
      expect(screen.getByRole('heading', { level: 1, name: /your wallet/i })).toBeInTheDocument();
    });

    it('renders 5-record recent transfers table with sent and received items and accessible controls', async () => {
      renderWithProviders(<CustomerDashboard />, { user: mockCustomerUser });

      await waitFor(() => {
        expect(screen.getByText('Recent transfers')).toBeInTheDocument();
        expect(screen.getByText('Your latest transfers sent and received.')).toBeInTheDocument();
        expect(screen.getAllByText(/25\.00/).length).toBeGreaterThan(0);
        expect(screen.getAllByText(/50\.00/).length).toBeGreaterThan(0);
      });

      expect(screen.getByRole('link', { name: /view all transfers/i })).toHaveAttribute('href', '/app/activity?type=transfers');
      expect(screen.getByRole('button', { name: /refresh recent transfers/i })).toBeInTheDocument();
    });
  });

  describe('WithdrawalSection Flow & Role Adaptation', () => {
    it('renders customer-specific simulated provider copy with no bank account claims', async () => {
      renderWithProviders(<WithdrawalSection />, { user: mockCustomerUser });

      await waitFor(() => {
        expect(
          screen.getByText(
            'Move money from your wallet balance through LedgerGuard’s simulated payout provider. The requested amount remains on hold until the provider confirms the result.'
          )
        ).toBeInTheDocument();
      });

      expect(screen.queryByText(/bank account/i)).not.toBeInTheDocument();
      expect(screen.queryByText(/settlement bank/i)).not.toBeInTheDocument();
      expect(screen.queryByText(/bank settlement/i)).not.toBeInTheDocument();
    });

    it('renders merchant-specific simulated provider copy with no bank account claims', async () => {
      renderWithProviders(<WithdrawalSection />, { user: mockMerchantUser });

      await waitFor(() => {
        expect(
          screen.getByText(
            'Move money from your merchant balance through LedgerGuard’s simulated payout provider. The requested amount remains on hold until the provider confirms the result.'
          )
        ).toBeInTheDocument();
      });

      expect(screen.queryByText(/bank account/i)).not.toBeInTheDocument();
      expect(screen.queryByText(/settlement bank/i)).not.toBeInTheDocument();
      expect(screen.queryByText(/bank settlement/i)).not.toBeInTheDocument();
    });

    it('opens confirmation modal with exact text and closing does not submit', async () => {
      const user = userEvent.setup();
      const withdrawSpy = vi.spyOn(financialApi, 'withdraw');

      renderWithProviders(<WithdrawalSection />, { user: mockCustomerUser });

      const amountInput = screen.getByLabelText(/amount to withdraw/i);
      await user.type(amountInput, '100.00');

      const initiateButton = screen.getByRole('button', { name: /withdraw funds/i });
      await user.click(initiateButton);

      expect(await screen.findByText('Withdraw ₹100.00?')).toBeInTheDocument();
      expect(
        screen.getByText('This amount will be reserved while the simulated payout provider processes the request.')
      ).toBeInTheDocument();

      const cancelButton = screen.getByRole('button', { name: /cancel/i });
      await user.click(cancelButton);

      expect(withdrawSpy).not.toHaveBeenCalled();
    });

    it('disables withdrawals when wallet status is not ACTIVE', async () => {
      vi.spyOn(walletApi, 'getMyWallet').mockResolvedValue({
        ...mockWallet,
        status: 'SUSPENDED',
      });

      renderWithProviders(<WithdrawalSection />, { user: mockCustomerUser });

      await waitFor(() => {
        expect(screen.getByText(/Your wallet status is SUSPENDED. Withdrawals are unavailable/i)).toBeInTheDocument();
      });

      const submitButton = screen.getByRole('button', { name: /withdraw funds/i });
      expect(submitButton).toBeDisabled();
    });

    it('presents uncertain outcome guidance when withdrawal results in network error', async () => {
      const user = userEvent.setup();
      vi.spyOn(financialApi, 'withdraw').mockRejectedValue(
        new ApiError({ status: 0, title: 'Network Error', detail: 'Connection timeout' })
      );

      renderWithProviders(<WithdrawalSection />, { user: mockCustomerUser });

      const amountInput = screen.getByLabelText(/amount to withdraw/i);
      await user.type(amountInput, '50.00');

      await user.click(screen.getByRole('button', { name: /withdraw funds/i }));
      await user.click(await screen.findByRole('button', { name: /confirm withdrawal/i }));

      expect(
        await screen.findByText(/The withdrawal outcome could not be confirmed. Check your Activity or refresh your wallet before retrying./i)
      ).toBeInTheDocument();
    });

    it('reuses the exact same idempotency key when retrying after an uncertain response', async () => {
      const user = userEvent.setup();
      const withdrawSpy = vi.spyOn(financialApi, 'withdraw')
        .mockRejectedValueOnce(new ApiError({ status: 0, title: 'Network Error', detail: 'Connection timeout' }))
        .mockResolvedValueOnce({
          payoutId: '77777777-7777-7777-7777-777777777777',
          ledgerAccountId: mockWallet.ledgerAccountId,
          amountMinor: '5000',
          currency: 'INR',
          status: 'PENDING',
          holdJournalTransactionId: '88888888-8888-8888-8888-888888888888',
          providerOperationId: 'provider-payout-1',
          createdAt: '2026-09-24T12:00:00Z',
          completedAt: null,
          failureReason: null,
          replayed: false,
        });

      renderWithProviders(<WithdrawalSection />, { user: mockCustomerUser });

      const amountInput = screen.getByLabelText(/amount to withdraw/i);
      await user.type(amountInput, '50.00');

      await user.click(screen.getByRole('button', { name: /withdraw funds/i }));
      await user.click(await screen.findByRole('button', { name: /confirm withdrawal/i }));

      await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());

      expect(
        await screen.findByText(/The withdrawal outcome could not be confirmed/i)
      ).toBeInTheDocument();

      await user.click(screen.getByRole('button', { name: /withdraw funds/i }));
      await user.click(await screen.findByRole('button', { name: /confirm withdrawal/i }));

      await waitFor(() => {
        expect(withdrawSpy).toHaveBeenCalledTimes(2);
      });
      const firstKey = withdrawSpy.mock.calls[0][1];
      const secondKey = withdrawSpy.mock.calls[1][1];
      expect(firstKey).toBeTruthy();
      expect(secondKey).toBe(firstKey);
    });
  });

  describe('TransferForm confirmation and balance checking', () => {
    it('displays clear helper text for destination wallet ID', () => {
      renderWithProviders(<TransferForm />, { user: mockCustomerUser });
      expect(screen.getByText('Enter the customer wallet ID that should receive these funds.')).toBeInTheDocument();
    });

    it('validates amount against available balance using integer arithmetic', async () => {
      const user = userEvent.setup();
      renderWithProviders(<TransferForm />, { user: mockCustomerUser });

      const destInput = screen.getByLabelText(/recipient wallet id/i);
      const amountInput = screen.getByLabelText(/amount \(inr\)/i);

      await user.type(destInput, '00000000-0000-0000-0000-000000000004');
      await user.type(amountInput, '500.00');

      const sendButton = screen.getByRole('button', { name: /send money/i });
      await user.click(sendButton);

      expect(await screen.findByText(/Amount exceeds available balance/i)).toBeInTheDocument();
    });

    it('opens confirmation modal and prevents double submission; closing does not submit', async () => {
      const user = userEvent.setup();
      const sendTransferSpy = vi.spyOn(transferApi, 'createTransfer').mockReturnValue(new Promise(() => {}));

      renderWithProviders(<TransferForm />, { user: mockCustomerUser });

      const destInput = screen.getByLabelText(/recipient wallet id/i);
      const amountInput = screen.getByLabelText(/amount \(inr\)/i);

      await user.type(destInput, '00000000-0000-0000-0000-000000000004');
      await user.type(amountInput, '100.00');

      const sendButton = screen.getByRole('button', { name: /send money/i });
      await user.click(sendButton);

      expect(await screen.findByText(/Send ₹100\.00\?/i)).toBeInTheDocument();

      const confirmButton = screen.getByRole('button', { name: /confirm transfer/i });
      await user.click(confirmButton);

      expect(sendTransferSpy).toHaveBeenCalledTimes(1);
      expect(confirmButton).toBeDisabled();
    });

    it('disables transfers when wallet status is not ACTIVE', async () => {
      vi.spyOn(walletApi, 'getMyWallet').mockResolvedValue({
        ...mockWallet,
        status: 'TERMINATED',
      });

      renderWithProviders(<TransferForm />, { user: mockCustomerUser });

      await waitFor(() => {
        expect(screen.getByText(/Your wallet status is TERMINATED. Transfers are unavailable/i)).toBeInTheDocument();
      });

      const submitButton = screen.getByRole('button', { name: /send money/i });
      expect(submitButton).toBeDisabled();
    });

    it('reuses the exact same idempotency key when retrying after an uncertain response', async () => {
      const user = userEvent.setup();
      const sendTransferSpy = vi.spyOn(transferApi, 'createTransfer')
        .mockRejectedValueOnce(new ApiError({ status: 0, title: 'Network Error', detail: 'Connection timeout' }))
        .mockResolvedValueOnce({
          transferId: '99999999-9999-9999-9999-999999999999',
          sourceLedgerAccountId: mockWallet.ledgerAccountId,
          destinationLedgerAccountId: '00000000-0000-0000-0000-000000000004',
          amountMinor: '10000',
          currency: 'INR',
          createdAt: '2026-09-24T12:00:00Z',
          replayed: false,
        });

      renderWithProviders(<TransferForm />, { user: mockCustomerUser });

      await user.type(screen.getByLabelText(/recipient wallet id/i), '00000000-0000-0000-0000-000000000004');
      await user.type(screen.getByLabelText(/amount \(inr\)/i), '100.00');

      await user.click(screen.getByRole('button', { name: /send money/i }));
      await user.click(await screen.findByRole('button', { name: /confirm transfer/i }));

      await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());

      expect(await screen.findByText('Transfer result unconfirmed')).toBeInTheDocument();

      await user.click(screen.getByRole('button', { name: /retry/i }));

      await waitFor(() => {
        expect(sendTransferSpy).toHaveBeenCalledTimes(2);
      });
      const firstKey = sendTransferSpy.mock.calls[0][1];
      const secondKey = sendTransferSpy.mock.calls[1][1];
      expect(firstKey).toBeTruthy();
      expect(secondKey).toBe(firstKey);
    });
  });

  describe('CustomerPaymentForm', () => {
    it('renders merchant payment ID label and helper text', async () => {
      renderWithProviders(<CustomerPaymentForm />, { user: mockCustomerUser });

      expect(screen.getByLabelText(/merchant payment id/i)).toBeInTheDocument();
      expect(screen.getByText('Ask the merchant for the payment ID shown on their dashboard.')).toBeInTheDocument();
      expect(await screen.findByText(/Available:/i)).toBeInTheDocument();
      expect(await screen.findByText('₹450.00')).toBeInTheDocument();
    });

    it('opens confirmation modal before paying merchant and closing does not submit', async () => {
      const user = userEvent.setup();
      const paySpy = vi.spyOn(financialApi, 'payMerchant').mockReturnValue(new Promise(() => {}));

      renderWithProviders(<CustomerPaymentForm />, { user: mockCustomerUser });

      const merchantInput = screen.getByLabelText(/merchant payment id/i);
      const amountInput = screen.getByLabelText(/^amount \(inr\)/i);

      await user.type(merchantInput, '00000000-0000-0000-0000-000000000002');
      await user.type(amountInput, '75.50');

      const submitButton = screen.getByRole('button', { name: /pay merchant/i });
      await user.click(submitButton);

      expect(await screen.findByText(/Pay ₹75\.50 to this merchant\?/i)).toBeInTheDocument();

      const cancelButton = screen.getByRole('button', { name: /cancel/i });
      await user.click(cancelButton);

      expect(paySpy).not.toHaveBeenCalled();
    });

    it('disables payment and displays warning when wallet status is not ACTIVE', async () => {
      vi.spyOn(walletApi, 'getMyWallet').mockResolvedValue({
        ...mockWallet,
        status: 'SUSPENDED',
      });

      renderWithProviders(<CustomerPaymentForm />, { user: mockCustomerUser });

      await waitFor(() => {
        expect(screen.getByText(/Your wallet status is SUSPENDED. Payments are unavailable/i)).toBeInTheDocument();
      });

      expect(screen.getByRole('button', { name: /pay merchant/i })).toBeDisabled();
    });

    it('shows uncertain outcome messaging on network failure', async () => {
      const user = userEvent.setup();
      vi.spyOn(financialApi, 'payMerchant').mockRejectedValue(
        new ApiError({ status: 0, title: 'Network Error', detail: 'Connection timeout' })
      );

      renderWithProviders(<CustomerPaymentForm />, { user: mockCustomerUser });

      await user.type(screen.getByLabelText(/merchant payment id/i), '00000000-0000-0000-0000-000000000002');
      await user.type(screen.getByLabelText(/^amount \(inr\)/i), '50.00');

      await user.click(screen.getByRole('button', { name: /pay merchant/i }));
      await user.click(await screen.findByRole('button', { name: /confirm payment/i }));

      expect(await screen.findByText('Payment outcome unconfirmed')).toBeInTheDocument();
      expect(
        screen.getByText('The payment outcome could not be confirmed. Check your Activity or refresh your wallet before retrying.')
      ).toBeInTheDocument();
    });

    it('reuses the exact same idempotency key when retrying after an uncertain response', async () => {
      const user = userEvent.setup();
      const paySpy = vi.spyOn(financialApi, 'payMerchant')
        .mockRejectedValueOnce(new ApiError({ status: 0, title: 'Network Error', detail: 'Connection timeout' }))
        .mockResolvedValueOnce({
          paymentId: '11111111-1111-1111-1111-111111111111',
          customerLedgerAccountId: mockWallet.ledgerAccountId,
          merchantLedgerAccountId: '00000000-0000-0000-0000-000000000002',
          grossAmountMinor: '5000',
          feeAmountMinor: '50',
          merchantNetAmountMinor: '4950',
          currency: 'INR',
          status: 'SUCCEEDED',
          journalTransactionId: '22222222-2222-2222-2222-222222222222',
          createdAt: '2026-09-24T12:00:00Z',
          completedAt: '2026-09-24T12:00:05Z',
          refundedAmountMinor: '0',
          refundStatus: 'NOT_REFUNDED',
          replayed: false,
        });

      renderWithProviders(<CustomerPaymentForm />, { user: mockCustomerUser });

      await user.type(screen.getByLabelText(/merchant payment id/i), '00000000-0000-0000-0000-000000000002');
      await user.type(screen.getByLabelText(/^amount \(inr\)/i), '50.00');

      await user.click(screen.getByRole('button', { name: /pay merchant/i }));
      await user.click(await screen.findByRole('button', { name: /confirm payment/i }));

      await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());

      expect(await screen.findByText('Payment outcome unconfirmed')).toBeInTheDocument();

      await user.click(screen.getByRole('button', { name: /retry/i }));

      await waitFor(() => {
        expect(paySpy).toHaveBeenCalledTimes(2);
      });
      const firstKey = paySpy.mock.calls[0][1];
      const secondKey = paySpy.mock.calls[1][1];
      expect(firstKey).toBeTruthy();
      expect(secondKey).toBe(firstKey);
    });
  });

  describe('CustomerFundingForm', () => {
    it('renders simulation rail disclosure and wallet context preview', async () => {
      renderWithProviders(<CustomerFundingForm />, { user: mockCustomerUser });

      expect(screen.getByText(/Simulation rail/i)).toBeInTheDocument();
      expect((await screen.findAllByText('₹450.00')).length).toBeGreaterThan(0);
      expect(screen.getByText('₹50.00')).toBeInTheDocument();
    });

    it('opens confirmation modal and adds money using minor unit conversion', async () => {
      const user = userEvent.setup();
      const addMoneySpy = vi.spyOn(financialApi, 'addMoney').mockReturnValue(new Promise(() => {}));

      renderWithProviders(<CustomerFundingForm />, { user: mockCustomerUser });

      const amountInput = screen.getByLabelText(/amount to add \(inr\)/i);
      await user.type(amountInput, '200.00');

      const submitButton = screen.getByRole('button', { name: /add money/i });
      await user.click(submitButton);

      expect(await screen.findByText(/Add ₹200\.00 to your wallet\?/i)).toBeInTheDocument();

      const confirmButton = screen.getByRole('button', { name: /confirm funding/i });
      await user.click(confirmButton);

      expect(addMoneySpy).toHaveBeenCalledWith({ amountMinor: '20000' }, expect.any(String));
      expect(confirmButton).toBeDisabled();
    });

    it('disables funding when wallet status is not ACTIVE', async () => {
      vi.spyOn(walletApi, 'getMyWallet').mockResolvedValue({
        ...mockWallet,
        status: 'SUSPENDED',
      });

      renderWithProviders(<CustomerFundingForm />, { user: mockCustomerUser });

      await waitFor(() => {
        expect(screen.getByText(/Your wallet status is SUSPENDED. Funding is unavailable/i)).toBeInTheDocument();
      });

      expect(screen.getByRole('button', { name: /add money/i })).toBeDisabled();
    });

    it('shows uncertain outcome messaging on network failure', async () => {
      const user = userEvent.setup();
      vi.spyOn(financialApi, 'addMoney').mockRejectedValue(
        new ApiError({ status: 0, title: 'Network Error', detail: 'Timeout' })
      );

      renderWithProviders(<CustomerFundingForm />, { user: mockCustomerUser });

      await user.type(screen.getByLabelText(/amount to add \(inr\)/i), '100.00');
      await user.click(screen.getByRole('button', { name: /add money/i }));
      await user.click(await screen.findByRole('button', { name: /confirm funding/i }));

      expect(await screen.findByText('Funding outcome unconfirmed')).toBeInTheDocument();
      expect(
        screen.getByText('The funding outcome could not be confirmed. Check your Activity or refresh your wallet before retrying.')
      ).toBeInTheDocument();
    });

    it('reuses the exact same idempotency key when retrying after an uncertain response', async () => {
      const user = userEvent.setup();
      const addMoneySpy = vi.spyOn(financialApi, 'addMoney')
        .mockRejectedValueOnce(new ApiError({ status: 0, title: 'Network Error', detail: 'Timeout' }))
        .mockResolvedValueOnce({
          fundingId: '33333333-3333-3333-3333-333333333333',
          ledgerAccountId: mockWallet.ledgerAccountId,
          amountMinor: '10000',
          currency: 'INR',
          status: 'SETTLED',
          providerOperationId: 'provider-123',
          createdAt: '2026-09-24T12:00:00Z',
          settledAt: '2026-09-24T12:00:05Z',
          replayed: false,
        });

      renderWithProviders(<CustomerFundingForm />, { user: mockCustomerUser });

      await user.type(screen.getByLabelText(/amount to add \(inr\)/i), '100.00');
      await user.click(screen.getByRole('button', { name: /add money/i }));
      await user.click(await screen.findByRole('button', { name: /confirm funding/i }));

      await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());

      expect(await screen.findByText('Funding outcome unconfirmed')).toBeInTheDocument();

      await user.click(screen.getByRole('button', { name: /retry/i }));

      await waitFor(() => {
        expect(addMoneySpy).toHaveBeenCalledTimes(2);
      });
      const firstKey = addMoneySpy.mock.calls[0][1];
      const secondKey = addMoneySpy.mock.calls[1][1];
      expect(firstKey).toBeTruthy();
      expect(secondKey).toBe(firstKey);
    });
  });

  describe('Role-specific visibility and regression protection', () => {
    it('FinancialHistory hides platform fee and merchant net when merchant=false', async () => {
      renderWithProviders(<FinancialHistory domain="payments" merchant={false} />, { user: mockCustomerUser });

      await waitFor(() => {
        expect(screen.getByText('Amount paid')).toBeInTheDocument();
      });

      expect(screen.queryByText('Platform fee')).not.toBeInTheDocument();
      expect(screen.queryByText('Merchant net')).not.toBeInTheDocument();
    });

    it('FinancialHistory preserves platform fee and merchant net when merchant=true', async () => {
      renderWithProviders(<FinancialHistory domain="payments" merchant={true} />, { user: mockMerchantUser });

      await waitFor(() => {
        expect(screen.getByText('Gross')).toBeInTheDocument();
      });

      expect(screen.getByText('Platform fee')).toBeInTheDocument();
      expect(screen.getByText('Merchant net')).toBeInTheDocument();
    });

    it('PaymentDetailPage hides fee and net for CUSTOMER and provides Back to payments button', async () => {
      const mockDetail: PaymentDetail = {
        payment: mockPayments[0],
        refundedAmountMinor: '0',
        refundableAmountMinor: '10000',
        refunds: { items: [], page: 0, size: 10, totalElements: 0, totalPages: 0 },
      };
      vi.spyOn(financialApi, 'paymentDetail').mockResolvedValue(mockDetail);

      renderWithProviders(
        <Routes>
          <Route path="/app/payments/:paymentId" element={<PaymentDetailPage />} />
        </Routes>,
        {
          initialEntries: [`/app/payments/${mockPayments[0].paymentId}`],
          user: mockCustomerUser,
        }
      );

      await waitFor(() => {
        expect(screen.getByText('Amount paid · INR')).toBeInTheDocument();
      });

      expect(screen.getByRole('link', { name: /back to payments/i })).toBeInTheDocument();
      expect(screen.queryByText('Platform fee')).not.toBeInTheDocument();
      expect(screen.queryByText('Merchant net amount')).not.toBeInTheDocument();
      expect(screen.getByText('Merchant payment ID')).toBeInTheDocument();
    });

    it('PaymentDetailPage preserves fee, net, and refund form for MERCHANT', async () => {
      const mockDetail: PaymentDetail = {
        payment: mockPayments[0],
        refundedAmountMinor: '0',
        refundableAmountMinor: '10000',
        refunds: { items: [], page: 0, size: 10, totalElements: 0, totalPages: 0 },
      };
      vi.spyOn(financialApi, 'paymentDetail').mockResolvedValue(mockDetail);

      renderWithProviders(
        <Routes>
          <Route path="/app/payments/:paymentId" element={<PaymentDetailPage />} />
        </Routes>,
        {
          initialEntries: [`/app/payments/${mockPayments[0].paymentId}`],
          user: mockMerchantUser,
        }
      );

      await waitFor(() => {
        expect(screen.getByText('Gross payment · INR')).toBeInTheDocument();
      });

      expect(screen.getByRole('link', { name: /back to customer payments/i })).toBeInTheDocument();
      expect(screen.getByText('Platform fee')).toBeInTheDocument();
      expect(screen.getByText('Merchant net amount')).toBeInTheDocument();
      expect(screen.getByText('Merchant wallet ID')).toBeInTheDocument();
      expect(screen.getByRole('button', { name: /issue refund/i })).toBeInTheDocument();
    });

    it('TransferDetailPage has Back to transfers button and collapsible Ledger proof accordion', async () => {
      const mockTransferDetail: TransferDetail = {
        transferId: '99999999-9999-9999-9999-999999999999',
        sourceLedgerAccountId: mockWallet.ledgerAccountId,
        destinationLedgerAccountId: '00000000-0000-0000-0000-000000000004',
        amountMinor: '2500',
        currency: 'INR',
        direction: 'OUTGOING',
        createdAt: '2026-09-24T12:00:00Z',
        journal: {
          journalTransactionId: '33333333-3333-3333-3333-333333333333',
          status: 'POSTED',
          entries: [
            {
              journalEntryId: '44444444-4444-4444-4444-444444444444',
              ledgerAccountId: mockWallet.ledgerAccountId,
              direction: 'DEBIT',
              amountMinor: '2500',
              currency: 'INR',
              sequenceOrder: 1,
            },
            {
              journalEntryId: '55555555-5555-5555-5555-555555555555',
              ledgerAccountId: '00000000-0000-0000-0000-000000000004',
              direction: 'CREDIT',
              amountMinor: '2500',
              currency: 'INR',
              sequenceOrder: 2,
            },
          ],
        },
      };
      vi.spyOn(transferApi, 'getTransferDetail').mockResolvedValue(mockTransferDetail);

      renderWithProviders(
        <Routes>
          <Route path="/app/transfers/:transferId" element={<TransferDetailPage />} />
        </Routes>,
        {
          initialEntries: [`/app/transfers/${mockTransferDetail.transferId}`],
          user: mockCustomerUser,
        }
      );

      await waitFor(() => {
        expect(screen.getByText('Ledger proof')).toBeInTheDocument();
      });

      expect(screen.getByRole('link', { name: /back to transfers/i })).toBeInTheDocument();
    });

    it('ActivityPage presents customer tabs and role-aware description', () => {
      renderWithProviders(<ActivityPage />, { user: mockCustomerUser });

      expect(screen.getByText('Review transfers, merchant payments, wallet funding and withdrawals.')).toBeInTheDocument();
      expect(screen.getByRole('tab', { name: 'Transfers' })).toBeInTheDocument();
      expect(screen.getByRole('tab', { name: 'Payments' })).toBeInTheDocument();
      expect(screen.getByRole('tab', { name: 'Funding' })).toBeInTheDocument();
      expect(screen.getByRole('tab', { name: 'Withdrawals' })).toBeInTheDocument();
    });

    it('ActivityPage presents merchant tabs and role-aware description', () => {
      renderWithProviders(<ActivityPage />, { user: mockMerchantUser });

      expect(screen.getByText('Review customer payments, refunds and payout activity.')).toBeInTheDocument();
      expect(screen.getByRole('tab', { name: 'Customer payments' })).toBeInTheDocument();
      expect(screen.getByRole('tab', { name: 'Payouts' })).toBeInTheDocument();
      expect(screen.queryByRole('tab', { name: 'Transfers' })).not.toBeInTheDocument();
      expect(screen.queryByRole('tab', { name: 'Funding' })).not.toBeInTheDocument();
    });

    it('ActivityPage accepts payouts query alias and canonicalizes to withdrawals for CUSTOMER', async () => {
      renderWithProviders(
        <Routes>
          <Route path="/app/activity" element={<ActivityPage />} />
        </Routes>,
        {
          initialEntries: ['/app/activity?type=payouts'],
          user: mockCustomerUser,
        }
      );

      const withdrawalsTab = screen.getByRole('tab', { name: 'Withdrawals' });
      expect(withdrawalsTab).toHaveAttribute('aria-selected', 'true');
    });

    it('ActivityPage falls back to role default when invalid tab type is provided', () => {
      const { unmount } = renderWithProviders(
        <Routes>
          <Route path="/app/activity" element={<ActivityPage />} />
        </Routes>,
        {
          initialEntries: ['/app/activity?type=unknown_tab'],
          user: mockCustomerUser,
        }
      );

      const customerTransfersTab = screen.getByRole('tab', { name: 'Transfers' });
      expect(customerTransfersTab).toHaveAttribute('aria-selected', 'true');
      unmount();

      renderWithProviders(
        <Routes>
          <Route path="/app/activity" element={<ActivityPage />} />
        </Routes>,
        {
          initialEntries: ['/app/activity?type=unknown_tab'],
          user: mockMerchantUser,
        }
      );

      const merchantPaymentsTab = screen.getByRole('tab', { name: 'Customer payments' });
      expect(merchantPaymentsTab).toHaveAttribute('aria-selected', 'true');
    });
  });
});
