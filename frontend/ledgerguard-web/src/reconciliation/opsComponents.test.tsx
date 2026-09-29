import { describe, it, expect, vi, beforeEach } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderWithProviders } from '../test/test-utils';
import { UserSummary } from '../shared/types/user.types';
import { AppLayout } from '../shared/layout/AppLayout';
import { ResolveCaseDialog } from './components/ResolveCaseDialog';
import { SnapshotRepairDialog } from './components/SnapshotRepairDialog';
import { CasesView } from './components/CasesView';
import { SnapshotRepairView } from './components/SnapshotRepairView';
import { RunTriggerDialog } from './components/RunTriggerDialog';
import { ReconciliationPage } from './pages/ReconciliationPage';
import { OpsDashboard } from '../ops/components/OpsDashboard';
import { ReconciliationCaseResponse } from './types/reconciliation.types';
import * as environmentModule from '../shared/config/environment';
import { reconciliationApi } from './api/reconciliationApi';
import { RouteMetadata } from '../app/router/RouteMetadata';
import { formatDateTime, formatEnumLabel, sanitizeOperatorDescription } from '../shared/utils/display';
import { RunsView } from './components/RunsView';

const mockOpsUser: UserSummary = {
  id: '00000000-0000-0000-0000-000000000099',
  email: 'ops.agent@ledgerguard.local',
  role: 'OPS',
  status: 'ACTIVE',
  createdAt: '2026-09-24T00:00:00Z',
  fullName: 'Ops Operator',
};

const sampleCase: ReconciliationCaseResponse = {
  id: '11111111-1111-1111-1111-111111111111',
  reconciliationItemId: '22222222-2222-2222-2222-222222222222',
  status: 'OPEN',
  assignedToUserId: null,
  resolvedByUserId: null,
  resolutionAction: null,
  resolutionNote: null,
  openedAt: '2026-09-27T10:00:00Z',
  updatedAt: '2026-09-27T10:00:00Z',
  resolvedAt: null,
  item: {
    id: '22222222-2222-2222-2222-222222222222',
    reconciliationRunId: '33333333-3333-3333-3333-333333333333',
    classification: 'DISCREPANCY',
    level: 'SNAPSHOT_CONSISTENCY',
    problemType: 'SNAPSHOT_MISMATCH',
    entityType: 'LEDGER_ACCOUNT',
    entityId: 'acc-999-wallet-balance',
    observedLocalStatus: null,
    expectedValue: 50000,
    actualValue: 40000,
    providerStatus: null,
    description: 'Snapshot drift detected between cached balance and journal lines.',
    detectedAt: '2026-09-27T10:00:00Z',
  },
};

describe('Operations Workspace & Reconciliation UI', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  describe('Production Failure Lab Navigation & Route Exclusion', () => {
    it('excludes Failure Lab from navigation in production mode', () => {
      vi.spyOn(environmentModule, 'isFailureLabEnabled').mockReturnValue(false);

      renderWithProviders(<AppLayout />, {
        user: mockOpsUser,
        initialEntries: ['/app'],
      });

      // Production OPS navigation links must include Dashboard, Reconciliation, Profile
      const reconLinks = screen.getAllByRole('link', { name: /reconciliation/i });
      expect(reconLinks.length).toBeGreaterThan(0);

      const profileLinks = screen.getAllByRole('link', { name: /profile/i });
      expect(profileLinks.length).toBeGreaterThan(0);

      // Failure Lab must be completely hidden
      const failureLabLinks = screen.queryAllByRole('link', { name: /failure lab/i });
      expect(failureLabLinks).toHaveLength(0);
    });

    it('includes Failure Lab in navigation when explicitly enabled in development or testbeds', () => {
      vi.spyOn(environmentModule, 'isFailureLabEnabled').mockReturnValue(true);

      renderWithProviders(<AppLayout />, {
        user: mockOpsUser,
        initialEntries: ['/app'],
      });

      const failureLabLinks = screen.getAllByRole('link', { name: /failure lab/i });
      expect(failureLabLinks.length).toBeGreaterThan(0);
    });
  });

  describe('Reconciliation Tab Switching with Search Params', () => {
    it('switches between Runs, Cases, and Snapshot Repair views', async () => {
      vi.spyOn(reconciliationApi, 'getRuns').mockResolvedValue({
        items: [],
        page: 0,
        size: 10,
        totalElements: 0,
        totalPages: 0,
      });
      vi.spyOn(reconciliationApi, 'getCases').mockResolvedValue({
        items: [],
        page: 0,
        size: 10,
        totalElements: 0,
        totalPages: 0,
      });

      const user = userEvent.setup();
      renderWithProviders(<ReconciliationPage />, {
        user: mockOpsUser,
        initialEntries: ['/app/reconciliation?tab=runs'],
      });

      expect(screen.getByText('Reconciliation Runs History')).toBeInTheDocument();

      // Click Investigation Cases tab
      const casesTab = screen.getByRole('tab', { name: /investigation cases/i });
      await user.click(casesTab);

      await waitFor(() => {
        expect(screen.getByPlaceholderText(/filter by run id/i)).toBeInTheDocument();
      });

      // Click Snapshot Repair tab
      const repairTab = screen.getByRole('tab', { name: /snapshot repair/i });
      await user.click(repairTab);

      await waitFor(() => {
        expect(screen.getByText('Case-Scoped Balance Snapshot Reconciliation')).toBeInTheDocument();
      });
    });
  });

  describe('Case Resolution Note Validation', () => {
    it('prevents resolution submission when note is empty or solely whitespace', async () => {
      const user = userEvent.setup();
      const onClose = vi.fn();

      renderWithProviders(
        <ResolveCaseDialog caseItem={sampleCase} open={true} onClose={onClose} />,
        { user: mockOpsUser }
      );

      const submitButton = screen.getByRole('button', { name: /confirm resolution/i });
      expect(submitButton).toBeDisabled();

      const noteInput = screen.getByLabelText(/resolution note/i);
      await user.type(noteInput, '     ');
      expect(submitButton).toBeDisabled();
    });

    it('rejects notes containing invalid control characters', async () => {
      const user = userEvent.setup();
      const onClose = vi.fn();

      renderWithProviders(
        <ResolveCaseDialog caseItem={sampleCase} open={true} onClose={onClose} />,
        { user: mockOpsUser }
      );

      const noteInput = screen.getByLabelText(/resolution note/i);
      // Type text containing a NUL control character
      await user.type(noteInput, 'Investigated and verified\u0000');

      const submitButton = screen.getByRole('button', { name: /confirm resolution/i });
      expect(submitButton).toBeDisabled();
      expect(screen.getByText(/contains invalid control characters/i)).toBeInTheDocument();
    });

    it('submits resolution note when input is valid (1-1000 chars)', async () => {
      const user = userEvent.setup();
      const onClose = vi.fn();
      const resolveSpy = vi.spyOn(reconciliationApi, 'resolveCase').mockResolvedValue({
        ...sampleCase,
        status: 'RESOLVED',
        resolutionNote: 'Fixed after checking source transaction.',
      });

      renderWithProviders(
        <ResolveCaseDialog caseItem={sampleCase} open={true} onClose={onClose} />,
        { user: mockOpsUser }
      );

      const noteInput = screen.getByLabelText(/resolution note/i);
      await user.type(noteInput, 'Root cause identified as ledger cache timing issue. Verified journal.');

      const submitButton = screen.getByRole('button', { name: /confirm resolution/i });
      expect(submitButton).not.toBeDisabled();
      await user.click(submitButton);

      await waitFor(() => {
        expect(resolveSpy).toHaveBeenCalledWith(
          sampleCase.id,
          expect.objectContaining({
            resolutionNote: 'Root cause identified as ledger cache timing issue. Verified journal.',
          })
        );
        expect(onClose).toHaveBeenCalled();
      }, { timeout: 15000 });
    }, 15000);
  });

  describe('Snapshot Repair Dialog Scoping & Confirmation', () => {
    it('is strictly case-scoped and does not provide an arbitrary account ID input', async () => {
      const onClose = vi.fn();

      renderWithProviders(
        <SnapshotRepairDialog caseItem={sampleCase} open={true} onClose={onClose} />,
        { user: mockOpsUser }
      );

      // Verifies clear explanation of re-synchronization to immutable journal entries
      expect(screen.getByText(/recompute the cached balance snapshot/i)).toBeInTheDocument();
      expect(screen.getByText(/Journal entries are append-only and strictly immutable/i)).toBeInTheDocument();

      // Displays the specific account ID from the case
      const accountElements = screen.getAllByText('acc-999-wallet-balance');
      expect(accountElements.length).toBeGreaterThan(0);

      // Confirms there is NO input field for typing an arbitrary account ID
      expect(screen.queryByRole('textbox')).not.toBeInTheDocument();
    });

    it('executes snapshot repair and displays repaired balance details from server', async () => {
      const user = userEvent.setup();
      const onClose = vi.fn();
      vi.spyOn(reconciliationApi, 'repairSnapshot').mockResolvedValue({
        caseId: sampleCase.id,
        accountId: 'acc-999-wallet-balance',
        repaired: true,
        oldBalanceMinor: 40000,
        newBalanceMinor: 50000,
        journalEntriesCount: 12,
        message: 'Snapshot successfully updated from journal entries ground truth.',
      });

      renderWithProviders(
        <SnapshotRepairDialog caseItem={sampleCase} open={true} onClose={onClose} />,
        { user: mockOpsUser }
      );

      const executeButton = screen.getByRole('button', { name: /execute repair/i });
      await user.click(executeButton);

      await waitFor(() => {
        expect(screen.getByText(/Snapshot Repaired Successfully/i)).toBeInTheDocument();
        expect(screen.getByText('12')).toBeInTheDocument();
        expect(screen.getByRole('button', { name: /done/i })).toBeInTheDocument();
      });
    });
  });

  describe('Snapshot Repair Claimant and Eligibility Rules', () => {
    it('renders Repair Snapshot button in CasesView when case is SNAPSHOT_MISMATCH and unassigned', async () => {
      vi.spyOn(reconciliationApi, 'getCases').mockResolvedValue({
        items: [{ ...sampleCase, assignedToUserId: null }],
        page: 0,
        size: 10,
        totalElements: 1,
        totalPages: 1,
      });

      renderWithProviders(<CasesView />, { user: mockOpsUser });

      await waitFor(() => {
        expect(screen.getByTitle('Repair Snapshot')).toBeInTheDocument();
      });
    });

    it('renders Repair Snapshot button in CasesView when case is assigned to the current user', async () => {
      vi.spyOn(reconciliationApi, 'getCases').mockResolvedValue({
        items: [{ ...sampleCase, assignedToUserId: mockOpsUser.id }],
        page: 0,
        size: 10,
        totalElements: 1,
        totalPages: 1,
      });

      renderWithProviders(<CasesView />, { user: mockOpsUser });

      await waitFor(() => {
        expect(screen.getByTitle('Repair Snapshot')).toBeInTheDocument();
      });
    });

    it('does NOT render Repair Snapshot button in CasesView when case is assigned to another operator', async () => {
      vi.spyOn(reconciliationApi, 'getCases').mockResolvedValue({
        items: [{ ...sampleCase, assignedToUserId: 'other-operator-user-id' }],
        page: 0,
        size: 10,
        totalElements: 1,
        totalPages: 1,
      });

      renderWithProviders(<CasesView />, { user: mockOpsUser });

      await waitFor(() => {
        expect(screen.queryByTitle('Repair Snapshot')).not.toBeInTheDocument();
      });
    });

    it('does NOT render Repair Snapshot button in CasesView when problemType is not SNAPSHOT_MISMATCH', async () => {
      const nonMismatchCase: ReconciliationCaseResponse = {
        ...sampleCase,
        assignedToUserId: null,
        item: {
          ...sampleCase.item,
          problemType: 'JOURNAL_DEBIT_CREDIT_IMBALANCE',
        },
      };

      vi.spyOn(reconciliationApi, 'getCases').mockResolvedValue({
        items: [nonMismatchCase],
        page: 0,
        size: 10,
        totalElements: 1,
        totalPages: 1,
      });

      renderWithProviders(<CasesView />, { user: mockOpsUser });

      await waitFor(() => {
        expect(screen.queryByTitle('Repair Snapshot')).not.toBeInTheDocument();
      });
    });

    it('does NOT render Repair Snapshot button in CasesView when case is RESOLVED', async () => {
      vi.spyOn(reconciliationApi, 'getCases').mockResolvedValue({
        items: [{ ...sampleCase, status: 'RESOLVED', assignedToUserId: null }],
        page: 0,
        size: 10,
        totalElements: 1,
        totalPages: 1,
      });

      renderWithProviders(<CasesView />, { user: mockOpsUser });

      await waitFor(() => {
        expect(screen.queryByTitle('Repair Snapshot')).not.toBeInTheDocument();
      });
    });

    it('renders Repair button in SnapshotRepairView when unassigned or assigned to current user, and shows warning when assigned to another', async () => {
      vi.spyOn(reconciliationApi, 'getCases').mockResolvedValue({
        items: [
          { ...sampleCase, id: 'case-unassigned', assignedToUserId: null },
          { ...sampleCase, id: 'case-mine', assignedToUserId: mockOpsUser.id },
          { ...sampleCase, id: 'case-other', assignedToUserId: 'other-user-999' },
        ],
        page: 0,
        size: 10,
        totalElements: 3,
        totalPages: 1,
      });

      renderWithProviders(<SnapshotRepairView />, { user: mockOpsUser });

      await waitFor(() => {
        const repairButtons = screen.getAllByRole('button', { name: /repair/i });
        // Only 2 repair buttons should exist (unassigned and assigned to current user)
        expect(repairButtons).toHaveLength(2);
        // Case assigned to another operator displays guidance text
        expect(screen.getByText('Assigned to another operator')).toBeInTheDocument();
      });
    });
  });

  describe('Run Trigger Dialog Timeout Uncertainty & Recovery', () => {
    it('disables start button and renders uncertainty alert on client timeout or network failure', async () => {
      const user = userEvent.setup();
      vi.spyOn(reconciliationApi, 'triggerRun').mockRejectedValue(
        new Error('Network request timed out after 30000ms')
      );

      renderWithProviders(
        <RunTriggerDialog open={true} onClose={vi.fn()} />,
        { user: mockOpsUser }
      );

      const startButton = screen.getByRole('button', { name: /start reconciliation/i });
      expect(startButton).toBeEnabled();

      await user.click(startButton);

      await waitFor(() => {
        expect(
          screen.getByText(/Request timed out or encountered network uncertainty/i)
        ).toBeInTheDocument();
        // Button must be disabled to prevent repeated submission
        expect(startButton).toBeDisabled();
      });
    });

    it('executes complete uncertain run recovery sequence: timeout -> alert with refresh -> authoritative status fetch -> discovered run display -> start button re-enabled', async () => {
      const user = userEvent.setup();
      const onClose = vi.fn();
      vi.spyOn(reconciliationApi, 'triggerRun').mockRejectedValue(
        new Error('Network request timed out after 30000ms')
      );

      renderWithProviders(
        <RunTriggerDialog open={true} onClose={onClose} />,
        { user: mockOpsUser }
      );

      const startButton = screen.getByRole('button', { name: /start reconciliation/i });
      await user.click(startButton);

      await waitFor(() => {
        expect(screen.getByText(/Request timed out or encountered network uncertainty/i)).toBeInTheDocument();
        expect(startButton).toBeDisabled();
      });

      const refreshButton = screen.getByRole('button', { name: /refresh run status/i });
      expect(refreshButton).toBeInTheDocument();

      // Server authoritative check: run finished as COMPLETED
      const getSummarySpy = vi.spyOn(reconciliationApi, 'getSummary').mockResolvedValue({
        openCases: 0,
        inReviewCases: 0,
        resolvedCases: 0,
        totalRuns: 1,
        latestRun: {
          id: 'a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d',
          status: 'COMPLETED',
          triggerSource: 'ON_DEMAND',
          discrepancyCount: 0,
          unresolvedCount: 0,
          startedAt: '2026-09-27T10:00:00Z',
          completedAt: '2026-09-27T10:00:15Z',
          journalsChecked: 50,
          accountsChecked: 10,
          operationsChecked: 4,
        },
      });

      await user.click(refreshButton);

      await waitFor(() => {
        expect(getSummarySpy).toHaveBeenCalled();
        expect(
          screen.getByText(/Latest run a1b2c3d4... is COMPLETED. Server confirms no active run in progress./i)
        ).toBeInTheDocument();
        expect(screen.getByText('Discovered Server Run')).toBeInTheDocument();
        expect(screen.getByText('a1b2c3d4...')).toBeInTheDocument();
        // Start button must now be re-enabled!
        expect(startButton).not.toBeDisabled();
      });

      // Dialog is not permanently locked; can be dismissed safely
      const cancelButton = screen.getByRole('button', { name: /cancel/i });
      await user.click(cancelButton);
      expect(onClose).toHaveBeenCalled();
    });

    it('retains Start button disabled when authoritative status check reveals a run is still RUNNING', async () => {
      const user = userEvent.setup();
      vi.spyOn(reconciliationApi, 'triggerRun').mockRejectedValue(
        new Error('Network request timed out after 30000ms')
      );

      renderWithProviders(
        <RunTriggerDialog open={true} onClose={vi.fn()} />,
        { user: mockOpsUser }
      );

      const startButton = screen.getByRole('button', { name: /start reconciliation/i });
      await user.click(startButton);

      await waitFor(() => {
        expect(screen.getByRole('button', { name: /refresh run status/i })).toBeInTheDocument();
      });

      vi.spyOn(reconciliationApi, 'getSummary').mockResolvedValue({
        openCases: 1,
        inReviewCases: 0,
        resolvedCases: 0,
        totalRuns: 1,
        latestRun: {
          id: 'b2c3d4e5-f6a7-8b9c-0d1e-2f3a4b5c6d7e',
          status: 'RUNNING',
          triggerSource: 'ON_DEMAND',
          discrepancyCount: 0,
          unresolvedCount: 0,
          startedAt: '2026-09-27T10:00:00Z',
          completedAt: null,
          journalsChecked: 10,
          accountsChecked: 2,
          operationsChecked: 0,
        },
      });

      const refreshButton = screen.getByRole('button', { name: /refresh run status/i });
      await user.click(refreshButton);

      await waitFor(() => {
        expect(
          screen.getByText(/Run b2c3d4e5... is currently RUNNING on the server. Please wait for completion./i)
        ).toBeInTheDocument();
        // Crucial safety check: Start button must remain disabled while run is active!
        expect(startButton).toBeDisabled();
      });
    });

    it('displays error alert on 409 Conflict without indefinite lockout', async () => {
      const user = userEvent.setup();
      const onClose = vi.fn();
      vi.spyOn(reconciliationApi, 'triggerRun').mockRejectedValue(
        new Error('Another reconciliation run is already in progress')
      );

      renderWithProviders(
        <RunTriggerDialog open={true} onClose={onClose} />,
        { user: mockOpsUser }
      );

      const startButton = screen.getByRole('button', { name: /start reconciliation/i });
      await user.click(startButton);

      await waitFor(() => {
        expect(
          screen.getByText('Another reconciliation run is already in progress')
        ).toBeInTheDocument();
        // Since this is not a timeout/uncertainty error, no "Refresh run status" button is shown
        expect(screen.queryByRole('button', { name: /refresh run status/i })).not.toBeInTheDocument();
      });

      // Dialog is cancelable
      const cancelButton = screen.getByRole('button', { name: /cancel/i });
      await user.click(cancelButton);
      expect(onClose).toHaveBeenCalled();
    });
  });

  describe('OpsDashboard Component States', () => {
    it('renders loading spinner while summary is loading', () => {
      vi.spyOn(reconciliationApi, 'getSummary').mockReturnValue(new Promise(() => {}));

      renderWithProviders(<OpsDashboard />, { user: mockOpsUser });

      expect(screen.getByText('Operations Command Center')).toBeInTheDocument();
      expect(screen.getByRole('progressbar')).toBeInTheDocument();
    });

    it('renders error alert with Retry button when summary query fails, and retries upon click', async () => {
      const user = userEvent.setup();
      let attempt = 0;
      const getSummarySpy = vi.spyOn(reconciliationApi, 'getSummary').mockImplementation(() => {
        attempt++;
        if (attempt === 1) {
          return Promise.reject(new Error('Internal Server Error'));
        }
        return Promise.resolve({
          openCases: 3,
          inReviewCases: 1,
          resolvedCases: 4,
          totalRuns: 2,
          latestRun: null,
        });
      });

      renderWithProviders(<OpsDashboard />, { user: mockOpsUser });

      await waitFor(() => {
        expect(screen.getByText('Failed to load operations summary metrics.')).toBeInTheDocument();
      });

      const retryButton = screen.getByRole('button', { name: /retry/i });
      await user.click(retryButton);

      await waitFor(() => {
        expect(getSummarySpy).toHaveBeenCalledTimes(2);
        expect(screen.getByText('3')).toBeInTheDocument(); // open cases
        expect(screen.getByText('4')).toBeInTheDocument(); // resolved cases
      });
    });

    it('renders empty database metrics and null latest run guidance when no runs exist', async () => {
      vi.spyOn(reconciliationApi, 'getSummary').mockResolvedValue({
        openCases: 0,
        inReviewCases: 0,
        resolvedCases: 0,
        totalRuns: 0,
        latestRun: null,
      });

      renderWithProviders(<OpsDashboard />, { user: mockOpsUser });

      await waitFor(() => {
        expect(screen.getByText('Operations Command Center')).toBeInTheDocument();
        expect(
          screen.getByText('No reconciliation runs recorded. Trigger a run to verify ledger invariants.')
        ).toBeInTheDocument();
        const zeroElements = screen.getAllByText('0');
        expect(zeroElements.length).toBeGreaterThanOrEqual(4);
      });
    });

    it('renders latest run details and opens run trigger dialog', async () => {
      const user = userEvent.setup();
      vi.spyOn(reconciliationApi, 'getSummary').mockResolvedValue({
        openCases: 2,
        inReviewCases: 1,
        resolvedCases: 10,
        totalRuns: 5,
        latestRun: {
          id: '12345678-abcd-ef01-2345-6789abcdef01',
          status: 'COMPLETED',
          triggerSource: 'ON_DEMAND',
          discrepancyCount: 0,
          unresolvedCount: 0,
          startedAt: '2026-09-27T08:00:00Z',
          completedAt: '2026-09-27T08:00:05Z',
          journalsChecked: 120,
          accountsChecked: 45,
          operationsChecked: 15,
        },
      });

      renderWithProviders(<OpsDashboard />, { user: mockOpsUser });

      await waitFor(() => {
        expect(screen.getByText('Latest Reconciliation Execution')).toBeInTheDocument();
        expect(screen.getByText('Completed')).toBeInTheDocument();
        expect(screen.getByRole('button', { name: /view run details/i })).toBeInTheDocument();
      });

      // Clicking Start Reconciliation opens trigger dialog
      const startButton = screen.getAllByRole('button', { name: /start reconciliation/i })[0];
      await user.click(startButton);

      await waitFor(() => {
        expect(screen.getByText('Start On-Demand Reconciliation')).toBeInTheDocument();
      });
    });

    it('renders TOTAL RECONCILIATION RUNS and provides clickable metric cards', async () => {
      vi.spyOn(reconciliationApi, 'getSummary').mockResolvedValue({
        openCases: 5,
        inReviewCases: 2,
        resolvedCases: 15,
        totalRuns: 8,
        latestRun: null,
      });

      renderWithProviders(<OpsDashboard />, { user: mockOpsUser });

      await waitFor(() => {
        expect(screen.getByText('TOTAL RECONCILIATION RUNS')).toBeInTheDocument();
        expect(screen.getByLabelText('View open cases')).toBeInTheDocument();
        expect(screen.getByLabelText('View in review cases')).toBeInTheDocument();
        expect(screen.getByLabelText('View resolved cases')).toBeInTheDocument();
        expect(screen.getByLabelText('View reconciliation runs')).toBeInTheDocument();
      });
    });
  });

  describe('Document Title & Route Metadata Integrity', () => {
    it('sets Reconciliation | LedgerGuard for /app/reconciliation and handles query params', () => {
      renderWithProviders(<RouteMetadata />, { initialEntries: ['/app/reconciliation'] });
      expect(document.title).toBe('Reconciliation | LedgerGuard');

      renderWithProviders(<RouteMetadata />, { initialEntries: ['/app/reconciliation?tab=runs'] });
      expect(document.title).toBe('Reconciliation | LedgerGuard');

      renderWithProviders(<RouteMetadata />, { initialEntries: ['/app/reconciliation?tab=cases&status=OPEN'] });
      expect(document.title).toBe('Reconciliation | LedgerGuard');

      renderWithProviders(<RouteMetadata />, { initialEntries: ['/app/reconciliation?tab=repair'] });
      expect(document.title).toBe('Reconciliation | LedgerGuard');
    });

    it('sets correct titles for Dashboard, Profile, and unknown routes', () => {
      renderWithProviders(<RouteMetadata />, { initialEntries: ['/app'] });
      expect(document.title).toBe('Dashboard | LedgerGuard');

      renderWithProviders(<RouteMetadata />, { initialEntries: ['/profile'] });
      expect(document.title).toBe('Profile | LedgerGuard');

      renderWithProviders(<RouteMetadata />, { initialEntries: ['/non-existent-route'] });
      expect(document.title).toBe('Page not found | LedgerGuard');
    });
  });

  describe('Reconciliation Header Action Single-Placement', () => {
    it('ReconciliationPage renders exactly one Start Reconciliation button in header', async () => {
      vi.spyOn(reconciliationApi, 'getRuns').mockResolvedValue({
        items: [],
        page: 0,
        size: 10,
        totalElements: 0,
        totalPages: 0,
      });

      renderWithProviders(<ReconciliationPage />, {
        user: mockOpsUser,
        initialEntries: ['/app/reconciliation?tab=runs'],
      });

      await waitFor(() => {
        const startButtons = screen.getAllByRole('button', { name: /start reconciliation/i });
        // Exactly one header button must exist, none inside RunsView
        expect(startButtons).toHaveLength(1);
      });
    });

    it('RunsView itself does not render a duplicate Start Reconciliation button', async () => {
      vi.spyOn(reconciliationApi, 'getRuns').mockResolvedValue({
        items: [],
        page: 0,
        size: 10,
        totalElements: 0,
        totalPages: 0,
      });

      renderWithProviders(<RunsView />, { user: mockOpsUser });

      await waitFor(() => {
        expect(screen.getByText('Reconciliation Runs History')).toBeInTheDocument();
        const startButton = screen.queryByRole('button', { name: /start reconciliation/i });
        expect(startButton).not.toBeInTheDocument();
      });
    });
  });

  describe('Exhaustive Enum & Timezone Formatting', () => {
    it('formats exhaustive backend enums to human readable titles', () => {
      expect(formatEnumLabel('ON_DEMAND')).toBe('On demand');
      expect(formatEnumLabel('SCHEDULED')).toBe('Scheduled');
      expect(formatEnumLabel('RUNNING')).toBe('Running');
      expect(formatEnumLabel('COMPLETED')).toBe('Completed');
      expect(formatEnumLabel('FAILED')).toBe('Failed');
      expect(formatEnumLabel('DISCREPANCY')).toBe('Discrepancy');
      expect(formatEnumLabel('UNRESOLVED')).toBe('Unresolved');
      expect(formatEnumLabel('JOURNAL_BALANCE')).toBe('Journal balance');
      expect(formatEnumLabel('SNAPSHOT_CONSISTENCY')).toBe('Snapshot consistency');
      expect(formatEnumLabel('PROVIDER_SETTLEMENT')).toBe('Provider settlement');
      expect(formatEnumLabel('SNAPSHOT_MISMATCH')).toBe('Snapshot mismatch');
      expect(formatEnumLabel('SNAPSHOT_REPAIRED')).toBe('Snapshot repaired');
      expect(formatEnumLabel('UNKNOWN_NEW_ENUM')).toBe('Unknown (UNKNOWN_NEW_ENUM)');
      expect(formatEnumLabel(null)).toBe('—');
    });

    it('formats timestamps into Asia/Kolkata (IST) format and provides honest fallback', () => {
      const formatted = formatDateTime('2026-09-28T17:01:45Z');
      expect(formatted).toContain('28 Sep 2026');
      expect(formatted).toContain('10:31:45');
      expect(formatted).toContain('PM IST');

      expect(formatDateTime(null, 'Not completed')).toBe('Not completed');
      expect(formatDateTime(undefined, '—')).toBe('—');
    });

    it('sanitizes operator-facing error descriptions', () => {
      expect(
        sanitizeOperatorDescription(
          'com.ledgerguard.reconciliation.domain.ReconciliationConflictException: Balance drift detected\n\tat com.ledgerguard.Engine.reconcile'
        )
      ).toBe('Balance drift detected');

      expect(sanitizeOperatorDescription('Standard safe description')).toBe('Standard safe description');
      expect(sanitizeOperatorDescription(null)).toBe('—');
    });
  });

  describe('Case Action State Gating & Difference Presentation', () => {
    it('enforces state gating for unassigned case: Claim is active, Resolve is disabled', async () => {
      const openCase: ReconciliationCaseResponse = {
        ...sampleCase,
        status: 'OPEN',
        assignedToUserId: null,
        item: {
          ...sampleCase.item,
          problemType: 'PROVIDER_STATUS_MISMATCH',
        },
      };

      vi.spyOn(reconciliationApi, 'getCases').mockResolvedValue({
        items: [openCase],
        page: 0,
        size: 10,
        totalElements: 1,
        totalPages: 1,
      });

      renderWithProviders(<CasesView />, { user: mockOpsUser });

      await waitFor(() => {
        // Claim button is enabled
        const claimBtn = screen.getByLabelText('Claim case');
        expect(claimBtn).toBeEnabled();

        // Resolve button is disabled because unassigned
        const resolveBtn = screen.getByLabelText('Claim case before resolving');
        expect(resolveBtn).toBeDisabled();
      });
    });

    it('disallows manual resolve on SNAPSHOT_MISMATCH cases', async () => {
      vi.spyOn(reconciliationApi, 'getCases').mockResolvedValue({
        items: [{ ...sampleCase, assignedToUserId: mockOpsUser.id }],
        page: 0,
        size: 10,
        totalElements: 1,
        totalPages: 1,
      });

      renderWithProviders(<CasesView />, { user: mockOpsUser });

      await waitFor(() => {
        // Repair snapshot button is present
        expect(screen.getByTitle('Repair Snapshot')).toBeInTheDocument();
        // Resolve case button must NOT be present for SNAPSHOT_MISMATCH
        expect(screen.queryByTitle('Resolve Case')).not.toBeInTheDocument();
      });
    });

    it('disables actions when case is assigned to another operator', async () => {
      const otherCase: ReconciliationCaseResponse = {
        ...sampleCase,
        status: 'IN_REVIEW',
        assignedToUserId: '99999999-9999-9999-9999-999999999999',
        item: {
          ...sampleCase.item,
          problemType: 'PROVIDER_STATUS_MISMATCH',
        },
      };

      vi.spyOn(reconciliationApi, 'getCases').mockResolvedValue({
        items: [otherCase],
        page: 0,
        size: 10,
        totalElements: 1,
        totalPages: 1,
      });

      renderWithProviders(<CasesView />, { user: mockOpsUser });

      await waitFor(() => {
        expect(screen.getByLabelText('Claim case (Assigned to another operator)')).toBeDisabled();
        expect(screen.getByLabelText('Resolve case (Assigned to another operator)')).toBeDisabled();
      });
    });

    it('renders Difference column with minor units in SnapshotRepairView', async () => {
      vi.spyOn(reconciliationApi, 'getCases').mockResolvedValue({
        items: [sampleCase],
        page: 0,
        size: 10,
        totalElements: 1,
        totalPages: 1,
      });

      renderWithProviders(<SnapshotRepairView />, { user: mockOpsUser });

      await waitFor(() => {
        expect(screen.getByText('Difference')).toBeInTheDocument();
        expect(screen.getByText('-10000 minor units')).toBeInTheDocument();
      });
    });
  });
});
