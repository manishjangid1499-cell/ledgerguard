import { describe, it, expect, vi, beforeEach } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { renderWithProviders } from '../../test/test-utils';
import { MerchantDashboard } from './MerchantDashboard';
import { WithdrawalSection } from './WithdrawalSection';
import { FinancialHistory } from './FinancialHistory';
import { ActivityPage } from '../pages/ActivityPage';
import { PaymentDetailPage } from '../pages/PaymentDetailPage';
import { walletApi } from '../../wallet/api/walletApi';
import { financialApi } from '../api';
import { Payment, PaymentDetail, Posted, Refund, Payout } from '../types';
import { Wallet } from '../../wallet/types';

const mockWallet: Wallet = {
  ledgerAccountId: '00000000-0000-0000-0000-000000000002',
  currency: 'INR',
  balanceMinor: '50000',
  availableBalanceMinor: '45000',
  activeHoldAmountMinor: '5000',
  updatedAt: '2026-09-24T00:00:00Z',
};

const mockSummary = {
  grossReceivedMinor: '100000',
  platformFeesMinor: '1000',
  netReceivedMinor: '99000',
  refundedAmountMinor: '15000',
  pendingPayoutsCount: 1,
  pendingPayoutsAmountMinor: '5000',
  currency: 'INR',
};

const mockPayments: Payment[] = [
  {
    paymentId: '11111111-1111-1111-1111-111111111111',
    customerLedgerAccountId: '00000000-0000-0000-0000-000000000003',
    merchantLedgerAccountId: mockWallet.ledgerAccountId,
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

describe('Merchant Experience Frontend Components', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    vi.spyOn(navigator.clipboard, 'writeText').mockResolvedValue();
    // Default successful API responses
    vi.spyOn(walletApi, 'getMyWallet').mockResolvedValue(mockWallet);
    vi.spyOn(financialApi, 'merchantSummary').mockResolvedValue(mockSummary);
    vi.spyOn(financialApi, 'payments').mockResolvedValue({
      items: mockPayments,
      page: 0,
      size: 5,
      totalElements: 1,
      totalPages: 1,
    });
  });

  describe('MerchantDashboard', () => {
    it('displays loading skeletons while initial queries are pending', () => {
      vi.spyOn(walletApi, 'getMyWallet').mockReturnValue(new Promise(() => {}));
      vi.spyOn(financialApi, 'merchantSummary').mockReturnValue(new Promise(() => {}));
      vi.spyOn(financialApi, 'payments').mockReturnValue(new Promise(() => {}));

      const { container } = renderWithProviders(<MerchantDashboard />);

      const skeletons = container.querySelectorAll('.MuiSkeleton-root');
      expect(skeletons.length).toBeGreaterThan(0);
    });

    it('displays API error alert and provides a working Retry action', async () => {
      const user = userEvent.setup();
      const getWalletSpy = vi.spyOn(walletApi, 'getMyWallet')
        .mockRejectedValueOnce(new Error('Failed to load wallet'))
        .mockResolvedValueOnce(mockWallet);

      renderWithProviders(<MerchantDashboard />);

      const errorAlert = await screen.findByText(/Failed to load wallet|Unable to load wallet balances/i);
      expect(errorAlert).toBeInTheDocument();

      const retryButton = screen.getByRole('button', { name: /retry/i });
      await user.click(retryButton);

      await waitFor(() => {
        expect(getWalletSpy).toHaveBeenCalledTimes(2);
        expect(screen.getByText('₹450.00')).toBeInTheDocument();
      });
    });

    it('limits recent payments list and includes View all payments link', async () => {
      renderWithProviders(<MerchantDashboard />);

      // Wait for table to render payments
      const grossCells = await screen.findAllByText('₹100.00');
      expect(grossCells.length).toBeGreaterThan(0);

      const viewAllButton = screen.getByRole('link', { name: /view all payments/i });
      expect(viewAllButton).toBeInTheDocument();
      expect(viewAllButton).toHaveAttribute('href', '/app/activity?type=payments');
    });

    it('merchant ID CopyButton has accessible name and provides copy feedback', async () => {
      const user = userEvent.setup();
      renderWithProviders(<MerchantDashboard />);

      await screen.findByText(mockWallet.ledgerAccountId);

      const copyBtn = screen.getByRole('button', { name: 'Copy merchant payment ID' });
      expect(copyBtn).toBeInTheDocument();

      await user.click(copyBtn);

      expect(navigator.clipboard.writeText).toHaveBeenCalledWith(mockWallet.ledgerAccountId);
      expect(screen.getByText('Copied')).toBeInTheDocument();
    });

    it('refresh and copy icon controls have accessible names', async () => {
      renderWithProviders(<MerchantDashboard />);

      await screen.findByText('Merchant overview');

      const refreshDashboardBtn = screen.getByRole('button', { name: /refresh wallet and summary/i });
      expect(refreshDashboardBtn).toBeInTheDocument();

      const refreshPaymentsBtn = screen.getByRole('button', { name: /refresh recent customer payments/i });
      expect(refreshPaymentsBtn).toBeInTheDocument();
    });

    it('renders customer refunds and platform fees with accurate financial labels and captions', async () => {
      renderWithProviders(<MerchantDashboard />);

      await screen.findByText('Customer refunds');
      expect(screen.getByText('Gross amount returned to customers')).toBeInTheDocument();
      expect(screen.getByText('Fees charged on completed payments')).toBeInTheDocument();
    });
  });

  describe('ActivityPage Tab Routing & Persistence', () => {
    it('persists active tab from URL search parameters', async () => {
      renderWithProviders(<ActivityPage />, {
        initialEntries: ['/app/activity?type=payouts'],
      });

      const payoutsTab = screen.getByRole('tab', { name: /payouts/i });
      expect(payoutsTab).toHaveAttribute('aria-selected', 'true');
    });

    it('falls back safely to default tab when URL contains an invalid tab type', async () => {
      renderWithProviders(<ActivityPage />, {
        initialEntries: ['/app/activity?type=invalid_unknown_type'],
      });

      const paymentsTab = screen.getByRole('tab', { name: /customer payments/i });
      expect(paymentsTab).toHaveAttribute('aria-selected', 'true');
    });
  });

  describe('FinancialHistory Filters', () => {
    it('resets page to 0 when filters are changed', async () => {
      const user = userEvent.setup();
      const paymentsSpy = vi.spyOn(financialApi, 'payments').mockResolvedValue({
        items: mockPayments,
        page: 2,
        size: 10,
        totalElements: 25,
        totalPages: 3,
      });

      renderWithProviders(<FinancialHistory domain="payments" merchant={true} />);

      await screen.findByText('Customer payments');

      const searchInput = screen.getByPlaceholderText(/enter 36-character uuid/i);
      await user.type(searchInput, '1111');

      await waitFor(() => {
        expect(paymentsSpy).toHaveBeenCalledWith(expect.objectContaining({ page: 0 }));
      });
    });
  });

  describe('PaymentDetailPage Refund Flow', () => {
    const mockDetail: PaymentDetail = {
      payment: mockPayments[0],
      refundedAmountMinor: '0',
      refundableAmountMinor: '10000', // ₹100.00 refundable
      refunds: { items: [], page: 0, size: 10, totalElements: 0, totalPages: 0 },
    };

    beforeEach(() => {
      vi.spyOn(financialApi, 'paymentDetail').mockResolvedValue(mockDetail);
    });

    it('opens refund confirmation dialog with correct calculated amount', async () => {
      const user = userEvent.setup();
      renderWithProviders(
        <Routes>
          <Route path="/app/payments/:paymentId" element={<PaymentDetailPage />} />
        </Routes>,
        { initialEntries: [`/app/payments/${mockPayments[0].paymentId}`] }
      );

      const issueRefundBtn = await screen.findByRole('button', { name: /issue refund/i });
      await user.click(issueRefundBtn);

      const amountInput = screen.getByLabelText(/refund amount/i);
      await user.type(amountInput, '25.00');

      const reviewBtn = screen.getByRole('button', { name: /review refund/i });
      await user.click(reviewBtn);

      const dialog = await screen.findByRole('dialog');
      expect(dialog).toBeInTheDocument();
      expect(within(dialog).getByText(/confirm refund of ₹25.00/i)).toBeInTheDocument();
      expect(within(dialog).getByText('₹75.00')).toBeInTheDocument();
    });

    it('prevents repeated submission while refund execution is in flight', async () => {
      const user = userEvent.setup();
      let resolveRefund: (val: Posted<Refund> & { paymentId: string }) => void;
      const refundPromise = new Promise<Posted<Refund> & { paymentId: string }>(resolve => { resolveRefund = resolve; });
      vi.spyOn(financialApi, 'refund').mockReturnValue(refundPromise);

      renderWithProviders(
        <Routes>
          <Route path="/app/payments/:paymentId" element={<PaymentDetailPage />} />
        </Routes>,
        { initialEntries: [`/app/payments/${mockPayments[0].paymentId}`] }
      );

      const issueRefundBtn = await screen.findByRole('button', { name: /issue refund/i });
      await user.click(issueRefundBtn);

      const amountInput = screen.getByLabelText(/refund amount/i);
      await user.type(amountInput, '25.00');

      const reviewBtn = screen.getByRole('button', { name: /review refund/i });
      await user.click(reviewBtn);

      const dialog = await screen.findByRole('dialog');
      const confirmBtn = within(dialog).getByRole('button', { name: /confirm refund/i });

      await user.click(confirmBtn);

      expect(confirmBtn).toBeDisabled();
      expect(within(dialog).getByText(/posting refund…/i)).toBeInTheDocument();

      resolveRefund!({ refundId: '999', replayed: false, paymentId: mockPayments[0].paymentId });
    });
  });

  describe('WithdrawalSection Flow', () => {
    it('Use maximum fills input with authoritative available balance', async () => {
      const user = userEvent.setup();
      renderWithProviders(<WithdrawalSection />);

      await screen.findByRole('heading', { name: /withdraw funds/i });

      const maxBtn = await screen.findByRole('button', { name: /use maximum/i });
      await user.click(maxBtn);

      const amountInput = screen.getByLabelText(/amount to withdraw/i) as HTMLInputElement;
      expect(amountInput.value).toBe('450.00');
    });

    it('opens withdrawal confirmation with correct remaining balance', async () => {
      const user = userEvent.setup();
      renderWithProviders(<WithdrawalSection />);

      await screen.findByRole('heading', { name: /withdraw funds/i });
      await screen.findByRole('button', { name: /use maximum/i });

      const amountInput = screen.getByLabelText(/amount to withdraw/i);
      await user.type(amountInput, '150.00');

      const withdrawBtn = screen.getByRole('button', { name: /^withdraw funds$/i });
      await user.click(withdrawBtn);

      const dialog = await screen.findByRole('dialog');
      expect(dialog).toBeInTheDocument();
      expect(within(dialog).getByText('₹300.00')).toBeInTheDocument();
      expect(within(dialog).getByText('₹150.00')).toBeInTheDocument();
    });

    it('prevents repeated submission while withdrawal is in flight', async () => {
      const user = userEvent.setup();
      let resolveWithdraw: (val: Posted<Payout>) => void;
      const withdrawPromise = new Promise<Posted<Payout>>(resolve => { resolveWithdraw = resolve; });
      vi.spyOn(financialApi, 'withdraw').mockReturnValue(withdrawPromise);

      renderWithProviders(<WithdrawalSection />);

      await screen.findByRole('heading', { name: /withdraw funds/i });
      await screen.findByRole('button', { name: /use maximum/i });

      const amountInput = screen.getByLabelText(/amount to withdraw/i);
      await user.type(amountInput, '100.00');

      const withdrawBtn = screen.getByRole('button', { name: /^withdraw funds$/i });
      await user.click(withdrawBtn);

      const dialog = await screen.findByRole('dialog');
      const confirmBtn = within(dialog).getByRole('button', { name: /confirm withdrawal/i });

      await user.click(confirmBtn);

      expect(confirmBtn).toBeDisabled();
      expect(within(dialog).getByText(/submitting…/i)).toBeInTheDocument();

      resolveWithdraw!({ payoutId: 'payout-123', replayed: false, amountMinor: '10000' });
    });
  });
});
