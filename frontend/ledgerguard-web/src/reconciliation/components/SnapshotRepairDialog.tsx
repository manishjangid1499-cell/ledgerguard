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
  Divider,
  Grid,
  Stack,
  Typography,
} from '@mui/material';
import BuildCircleOutlinedIcon from '@mui/icons-material/BuildCircleOutlined';
import CheckCircleIcon from '@mui/icons-material/CheckCircle';
import { ReconciliationCaseResponse, SnapshotRepairResponse } from '../types/reconciliation.types';
import { useRepairSnapshot } from '../hooks/useReconciliation';
import { formatMinorUnitsToInr } from '../../shared/utils/money';
import { getErrorMessage } from '../../shared/api/errorMessage';

interface SnapshotRepairDialogProps {
  caseItem: ReconciliationCaseResponse | null;
  open: boolean;
  onClose: () => void;
  onSuccess?: () => void;
}

export const SnapshotRepairDialog: React.FC<SnapshotRepairDialogProps> = ({
  caseItem,
  open,
  onClose,
  onSuccess,
}) => {
  const repairMutation = useRepairSnapshot();
  const [result, setResult] = React.useState<SnapshotRepairResponse | null>(null);

  if (!open || !caseItem) return null;

  const handleClose = () => {
    if (repairMutation.isPending) return;
    setResult(null);
    repairMutation.reset();
    onClose();
  };

  const handleRepair = async () => {
    try {
      const response = await repairMutation.mutateAsync(caseItem.id);
      setResult(response);
      onSuccess?.();
    } catch {
      // captured in repairMutation.error
    }
  };

  return (
    <Dialog
      open={open}
      onClose={handleClose}
      maxWidth="sm"
      fullWidth
      aria-labelledby="snapshot-repair-dialog-title"
    >
      <DialogTitle id="snapshot-repair-dialog-title" sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
        <BuildCircleOutlinedIcon color="primary" />
        Repair Balance Snapshot
      </DialogTitle>
      <DialogContent dividers>
        <Stack spacing={2.5}>
          {!result ? (
            <>
              <DialogContentText>
                This operation will rebuild and recompute the cached balance snapshot for ledger account{' '}
                <strong>{caseItem.item.entityId}</strong> from immutable POSTED journal entries.
              </DialogContentText>

              <Alert severity="info" variant="outlined">
                <strong>Financial Invariant:</strong> Journal entries are append-only and strictly immutable.
                Snapshot repair changes the snapshot only, updating the cached balance to match the verified sum
                of POSTED journal entries without modifying ledger entries or journals.
              </Alert>

              <Box sx={{ p: 2, bgcolor: 'background.default', borderRadius: 1 }}>
                <Grid container spacing={1.5}>
                  <Grid size={{ xs: 12, sm: 4 }}>
                    <Typography variant="caption" color="text.secondary">Case ID</Typography>
                    <Typography variant="body2" sx={{ fontFamily: 'monospace' }}>{caseItem.id.slice(0, 8)}...</Typography>
                  </Grid>
                  <Grid size={{ xs: 12, sm: 8 }}>
                    <Typography variant="caption" color="text.secondary">Account ID</Typography>
                    <Typography variant="body2" sx={{ fontFamily: 'monospace' }}>{caseItem.item.entityId}</Typography>
                  </Grid>
                  <Grid size={{ xs: 12, sm: 6 }}>
                    <Typography variant="caption" color="text.secondary">Reported Snapshot Value</Typography>
                    <Typography variant="body2">
                      {caseItem.item.actualValue !== null
                        ? formatMinorUnitsToInr(caseItem.item.actualValue)
                        : 'N/A'}
                    </Typography>
                  </Grid>
                  <Grid size={{ xs: 12, sm: 6 }}>
                    <Typography variant="caption" color="text.secondary">Expected Journal Sum</Typography>
                    <Typography variant="body2" sx={{ fontWeight: 600 }}>
                      {caseItem.item.expectedValue !== null
                        ? formatMinorUnitsToInr(caseItem.item.expectedValue)
                        : 'N/A'}
                    </Typography>
                  </Grid>
                </Grid>
              </Box>

              {repairMutation.isError && (
                <Alert severity="error">
                  {getErrorMessage(repairMutation.error, 'Failed to repair balance snapshot.')}
                </Alert>
              )}
            </>
          ) : (
            <Stack spacing={2} sx={{ alignItems: 'center', py: 1 }}>
              <CheckCircleIcon color="success" sx={{ fontSize: 48 }} />
              <Typography variant="h6" sx={{ color: 'success.main', textAlign: 'center' }}>
                Snapshot Repaired Successfully
              </Typography>
              <Typography variant="body2" color="text.secondary" sx={{ textAlign: 'center' }}>
                {result.message}
              </Typography>

              <Divider flexItem sx={{ my: 1 }} />

              <Box sx={{ width: '100%', p: 2, bgcolor: 'background.default', borderRadius: 1 }}>
                <Grid container spacing={1.5}>
                  <Grid size={{ xs: 6 }}>
                    <Typography variant="caption" color="text.secondary">Previous Snapshot</Typography>
                    <Typography variant="body2">{formatMinorUnitsToInr(result.oldBalanceMinor)}</Typography>
                  </Grid>
                  <Grid size={{ xs: 6 }}>
                    <Typography variant="caption" color="text.secondary">Repaired Balance</Typography>
                    <Typography variant="body2" sx={{ fontWeight: 600, color: 'primary.main' }}>
                      {formatMinorUnitsToInr(result.newBalanceMinor)}
                    </Typography>
                  </Grid>
                  <Grid size={{ xs: 12 }}>
                    <Typography variant="caption" color="text.secondary">Journal Entries Recalculated</Typography>
                    <Typography variant="body2">{result.journalEntriesCount}</Typography>
                  </Grid>
                </Grid>
              </Box>
            </Stack>
          )}
        </Stack>
      </DialogContent>
      <DialogActions sx={{ px: 3, py: 2 }}>
        {!result ? (
          <>
            <Button
              onClick={handleClose}
              variant="outlined"
              disabled={repairMutation.isPending}
            >
              Cancel
            </Button>
            <Button
              onClick={handleRepair}
              variant="contained"
              color="primary"
              disabled={repairMutation.isPending}
              startIcon={repairMutation.isPending ? <CircularProgress size={16} color="inherit" /> : null}
            >
              {repairMutation.isPending ? 'Repairing...' : 'Execute Repair'}
            </Button>
          </>
        ) : (
          <Button onClick={handleClose} variant="contained" color="primary">
            Done
          </Button>
        )}
      </DialogActions>
    </Dialog>
  );
};
