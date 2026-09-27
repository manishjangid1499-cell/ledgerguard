import React from 'react';
import {
  Alert,
  Box,
  Button,
  CircularProgress,
  Dialog,
  DialogActions,
  DialogContent,
  DialogContentText,
  DialogTitle,
  Paper,
  Stack,
  Typography,
} from '@mui/material';
import PlayArrowIcon from '@mui/icons-material/PlayArrow';
import RefreshIcon from '@mui/icons-material/Refresh';
import { useTriggerReconciliationRun } from '../hooks/useReconciliation';
import { StatusBadge } from '../../shared/components/StatusBadge';
import { reconciliationApi } from '../api/reconciliationApi';
import { ReconciliationRunSummaryResponse } from '../types/reconciliation.types';

interface RunTriggerDialogProps {
  open: boolean;
  onClose: () => void;
  onSuccess?: (run?: ReconciliationRunSummaryResponse) => void;
}

export const RunTriggerDialog: React.FC<RunTriggerDialogProps> = ({
  open,
  onClose,
  onSuccess,
}) => {
  const triggerMutation = useTriggerReconciliationRun();
  const [submittedOnce, setSubmittedOnce] = React.useState(false);
  const [isCheckingStatus, setIsCheckingStatus] = React.useState(false);
  const [discoveredRun, setDiscoveredRun] = React.useState<ReconciliationRunSummaryResponse | null>(null);
  const [statusCheckMessage, setStatusCheckMessage] = React.useState<string | null>(null);
  const [isRecovered, setIsRecovered] = React.useState(false);

  const isTimeoutOrNetworkError = Boolean(
    triggerMutation.isError &&
      (triggerMutation.error.message?.toLowerCase().includes('timeout') ||
        triggerMutation.error.message?.toLowerCase().includes('network') ||
        triggerMutation.error.message?.toLowerCase().includes('uncertain') ||
        triggerMutation.error.message?.toLowerCase().includes('aborted'))
  );

  const handleStart = async () => {
    try {
      setSubmittedOnce(true);
      setIsRecovered(false);
      setStatusCheckMessage(null);
      setDiscoveredRun(null);
      const run = await triggerMutation.mutateAsync();
      onSuccess?.(run);
      onClose();
    } catch {
      // Error captured by mutation state
    }
  };

  const handleRefreshRunStatus = async () => {
    try {
      setIsCheckingStatus(true);
      setStatusCheckMessage(null);
      const summary = await reconciliationApi.getSummary();
      if (summary.latestRun) {
        setDiscoveredRun(summary.latestRun);
        if (summary.latestRun.status === 'RUNNING') {
          setStatusCheckMessage(
            `Run ${summary.latestRun.id.slice(0, 8)}... is currently RUNNING on the server. Please wait for completion.`
          );
        } else {
          setStatusCheckMessage(
            `Latest run ${summary.latestRun.id.slice(0, 8)}... is ${summary.latestRun.status}. Server confirms no active run in progress.`
          );
          setIsRecovered(true);
        }
      } else {
        setStatusCheckMessage('Server confirms no reconciliation run is active.');
        setIsRecovered(true);
      }
    } catch {
      setStatusCheckMessage('Failed to refresh run status. Please check your network connection.');
    } finally {
      setIsCheckingStatus(false);
    }
  };

  const handleClose = () => {
    if (!triggerMutation.isPending) {
      triggerMutation.reset();
      setSubmittedOnce(false);
      setIsCheckingStatus(false);
      setDiscoveredRun(null);
      setStatusCheckMessage(null);
      setIsRecovered(false);
      onClose();
    }
  };

  const isStartDisabled =
    triggerMutation.isPending || (submittedOnce && isTimeoutOrNetworkError && !isRecovered);

  return (
    <Dialog
      open={open}
      onClose={handleClose}
      maxWidth="sm"
      fullWidth
      aria-labelledby="run-trigger-dialog-title"
    >
      <DialogTitle id="run-trigger-dialog-title">
        Start On-Demand Reconciliation
      </DialogTitle>
      <DialogContent>
        {triggerMutation.isError && (
          <Alert
            severity={isTimeoutOrNetworkError ? (isRecovered ? 'info' : 'warning') : 'error'}
            sx={{ mb: 2 }}
            action={
              isTimeoutOrNetworkError ? (
                <Button
                  color="inherit"
                  size="small"
                  startIcon={
                    isCheckingStatus ? (
                      <CircularProgress size={14} color="inherit" />
                    ) : (
                      <RefreshIcon fontSize="small" />
                    )
                  }
                  onClick={handleRefreshRunStatus}
                  disabled={isCheckingStatus}
                >
                  Refresh run status
                </Button>
              ) : undefined
            }
          >
            {isTimeoutOrNetworkError
              ? statusCheckMessage ||
                'Request timed out or encountered network uncertainty. The reconciliation scan may already be executing on the server under the advisory lock. Do not resubmit; refresh run status or inspect the Runs table.'
              : triggerMutation.error.message ||
                'Failed to execute reconciliation run. Another run may already be in progress.'}
          </Alert>
        )}

        {discoveredRun && (
          <Paper variant="outlined" sx={{ p: 1.5, mb: 2, bgcolor: 'background.default' }}>
            <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mb: 0.5 }}>
              Discovered Server Run
            </Typography>
            <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
              <Typography variant="body2" sx={{ fontFamily: 'monospace', fontWeight: 600 }}>
                {discoveredRun.id.slice(0, 8)}...
              </Typography>
              <StatusBadge status={discoveredRun.status} />
            </Stack>
            <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 0.5 }}>
              Trigger: {discoveredRun.triggerSource} | Discrepancies: {discoveredRun.discrepancyCount} | Unresolved: {discoveredRun.unresolvedCount}
            </Typography>
          </Paper>
        )}

        {triggerMutation.isPending ? (
          <Stack spacing={2} sx={{ alignItems: 'center', py: 3 }}>
            <CircularProgress size={44} />
            <Typography variant="body1" sx={{ fontWeight: 500 }}>
              Executing reconciliation scan...
            </Typography>
            <Typography variant="body2" color="text.secondary" sx={{ textAlign: 'center' }}>
              Scanning immutable journal transactions, verifying account balance snapshots, and
              validating PSP settlements. This executes synchronously under an advisory lock.
            </Typography>
          </Stack>
        ) : (
          <Box>
            <DialogContentText sx={{ mb: 2 }}>
              Are you sure you want to trigger a full three-level reconciliation run?
            </DialogContentText>
            <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5 }}>
              <strong>Level 1 — Journal Balance:</strong> Scans all POSTED transactions for balance and entry completeness.
            </Typography>
            <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5 }}>
              <strong>Level 2 — Snapshot Consistency:</strong> Reconstructs expected balances from immutable journal entries and compares them against balance snapshots.
            </Typography>
            <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5 }}>
              <strong>Level 3 — Provider Settlement:</strong> Reconciles funding operations and payouts against authoritative PSP records.
            </Typography>
            <Typography variant="caption" color="text.secondary" sx={{ display: 'block' }}>
              Note: This is a detection scan and will not mutate financial ledgers.
            </Typography>
          </Box>
        )}
      </DialogContent>
      <DialogActions sx={{ px: 3, pb: 2 }}>
        <Button onClick={handleClose} disabled={triggerMutation.isPending} color="inherit">
          Cancel
        </Button>
        <Button
          onClick={handleStart}
          variant="contained"
          color="primary"
          disabled={isStartDisabled}
          startIcon={
            triggerMutation.isPending ? <CircularProgress size={16} color="inherit" /> : <PlayArrowIcon />
          }
        >
          {triggerMutation.isPending ? 'Running Scan...' : 'Start Reconciliation'}
        </Button>
      </DialogActions>
    </Dialog>
  );
};
