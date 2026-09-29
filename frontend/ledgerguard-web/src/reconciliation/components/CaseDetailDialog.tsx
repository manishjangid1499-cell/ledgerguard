import React, { useState } from 'react';
import {
  Alert,
  Box,
  Button,
  CircularProgress,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Divider,
  Grid,
  Paper,
  Stack,
  Typography,
} from '@mui/material';
import AssignmentIndIcon from '@mui/icons-material/AssignmentInd';
import CheckCircleIcon from '@mui/icons-material/CheckCircle';
import BuildCircleOutlinedIcon from '@mui/icons-material/BuildCircleOutlined';
import { StatusBadge } from '../../shared/components/StatusBadge';
import { CopyButton } from '../../shared/components/CopyButton';
import { useAuth } from '../../auth/hooks/useAuth';
import { useClaimCase, useReconciliationCase } from '../hooks/useReconciliation';
import { ResolveCaseDialog } from './ResolveCaseDialog';
import { SnapshotRepairDialog } from './SnapshotRepairDialog';
import { formatMinorUnitsToInr } from '../../shared/utils/money';
import { getErrorMessage } from '../../shared/api/errorMessage';
import {
  formatDateTime,
  formatEnumLabel,
  sanitizeOperatorDescription,
} from '../../shared/utils/display';

interface CaseDetailDialogProps {
  caseId: string | null;
  open: boolean;
  onClose: () => void;
  onViewRun?: (runId: string) => void;
}

export const CaseDetailDialog: React.FC<CaseDetailDialogProps> = ({
  caseId,
  open,
  onClose,
  onViewRun,
}) => {
  const { user } = useAuth();
  const { data: caseItem, isLoading, error } = useReconciliationCase(caseId || '');
  const claimMutation = useClaimCase();

  const [resolveOpen, setResolveOpen] = useState(false);
  const [repairOpen, setRepairOpen] = useState(false);

  if (!open) return null;

  const isAssignedToMe = caseItem?.assignedToUserId === user?.id;
  const isAssignedToOther = caseItem?.assignedToUserId !== null && !isAssignedToMe;
  const isUnassigned = caseItem?.assignedToUserId === null;
  const isResolved = caseItem?.status === 'RESOLVED';
  const isSnapshotMismatch = caseItem?.item.problemType === 'SNAPSHOT_MISMATCH';

  const handleClaim = async () => {
    if (!caseItem) return;
    try {
      await claimMutation.mutateAsync(caseItem.id);
    } catch {
      // error in claimMutation.error
    }
  };

  return (
    <>
      <Dialog
        open={open}
        onClose={onClose}
        maxWidth="md"
        fullWidth
        aria-labelledby="case-detail-dialog-title"
      >
        <DialogTitle
          id="case-detail-dialog-title"
          sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}
        >
          <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center' }}>
            <span>Reconciliation Case Details</span>
            {caseItem && <StatusBadge status={caseItem.status} />}
          </Stack>
        </DialogTitle>
        <DialogContent dividers sx={{ maxHeight: 'calc(100vh - 200px)', overflowY: 'auto' }}>
          {isLoading && (
            <Stack sx={{ alignItems: 'center', py: 4 }}>
              <CircularProgress />
            </Stack>
          )}

          {error && (
            <Alert severity="error" sx={{ mb: 2 }}>
              Failed to load reconciliation case details.
            </Alert>
          )}

          {claimMutation.isError && (
            <Alert severity="error" sx={{ mb: 2 }}>
              {getErrorMessage(claimMutation.error, 'Failed to claim case.')}
            </Alert>
          )}

          {caseItem && (
            <Stack spacing={3}>
              <Grid container spacing={2}>
                <Grid size={{ xs: 12, sm: 6 }}>
                  <Typography variant="caption" color="text.secondary">Case ID</Typography>
                  <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
                    <Typography variant="body2" sx={{ fontFamily: 'monospace' }}>{caseItem.id}</Typography>
                    <CopyButton value={caseItem.id} label={`case ID ${caseItem.id}`} />
                  </Stack>
                </Grid>
                <Grid size={{ xs: 12, sm: 6 }}>
                  <Typography variant="caption" color="text.secondary">Run ID</Typography>
                  <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
                    <Typography
                      variant="body2"
                      sx={{
                        fontFamily: 'monospace',
                        cursor: onViewRun ? 'pointer' : 'default',
                        color: onViewRun ? 'primary.main' : 'inherit',
                        textDecoration: onViewRun ? 'underline' : 'none',
                      }}
                      onClick={() => onViewRun?.(caseItem.item.reconciliationRunId)}
                    >
                      {caseItem.item.reconciliationRunId}
                    </Typography>
                    <CopyButton value={caseItem.item.reconciliationRunId} label={`run ID ${caseItem.item.reconciliationRunId}`} />
                  </Stack>
                </Grid>

                <Grid size={{ xs: 6, sm: 3 }}>
                  <Typography variant="caption" color="text.secondary">Classification</Typography>
                  <Box sx={{ mt: 0.5 }}>
                    <StatusBadge status={caseItem.item.classification} />
                  </Box>
                </Grid>
                <Grid size={{ xs: 6, sm: 3 }}>
                  <Typography variant="caption" color="text.secondary">Level</Typography>
                  <Typography variant="body2" sx={{ fontWeight: 500 }}>
                    {formatEnumLabel(caseItem.item.level)}
                  </Typography>
                </Grid>
                <Grid size={{ xs: 6, sm: 3 }}>
                  <Typography variant="caption" color="text.secondary">Problem Type</Typography>
                  <Typography variant="body2" sx={{ fontWeight: 600, color: 'error.main' }}>
                    {formatEnumLabel(caseItem.item.problemType)}
                  </Typography>
                </Grid>
                <Grid size={{ xs: 6, sm: 3 }}>
                  <Typography variant="caption" color="text.secondary">Assigned Operator</Typography>
                  <Typography variant="body2">
                    {caseItem.assignedToUserId ? (
                      isAssignedToMe ? (
                        <strong>Assigned to You</strong>
                      ) : (
                        <span style={{ fontFamily: 'monospace' }}>{caseItem.assignedToUserId.slice(0, 8)}...</span>
                      )
                    ) : (
                      <em>Unassigned</em>
                    )}
                  </Typography>
                </Grid>
              </Grid>

              <Divider />

              <Box>
                <Typography variant="subtitle2" color="text.secondary" gutterBottom>
                  Discrepancy Invariant Assessment
                </Typography>
                <Paper variant="outlined" sx={{ p: 2, bgcolor: 'background.default' }}>
                  <Grid container spacing={2}>
                    <Grid size={{ xs: 12, sm: 4 }}>
                      <Typography variant="caption" color="text.secondary">Affected Entity</Typography>
                      <Stack direction="row" spacing={0.5} sx={{ alignItems: 'center' }}>
                        <Typography variant="body2" sx={{ fontFamily: 'monospace' }}>
                          {formatEnumLabel(caseItem.item.entityType)}: {caseItem.item.entityId}
                        </Typography>
                        <CopyButton value={caseItem.item.entityId} label={`entity ID ${caseItem.item.entityId}`} />
                      </Stack>
                    </Grid>
                    {caseItem.item.expectedValue !== null && (
                      <Grid size={{ xs: 6, sm: 4 }}>
                        <Typography variant="caption" color="text.secondary">Expected Value</Typography>
                        <Typography variant="body2" sx={{ fontWeight: 600 }}>
                          {formatMinorUnitsToInr(caseItem.item.expectedValue)}
                        </Typography>
                      </Grid>
                    )}
                    {caseItem.item.actualValue !== null && (
                      <Grid size={{ xs: 6, sm: 4 }}>
                        <Typography variant="caption" color="text.secondary">Actual Value</Typography>
                        <Typography variant="body2" sx={{ fontWeight: 600 }}>
                          {formatMinorUnitsToInr(caseItem.item.actualValue)}
                        </Typography>
                      </Grid>
                    )}
                    {caseItem.item.observedLocalStatus && (
                      <Grid size={{ xs: 6, sm: 4 }}>
                        <Typography variant="caption" color="text.secondary">Observed Local Status</Typography>
                        <Typography variant="body2">{formatEnumLabel(caseItem.item.observedLocalStatus)}</Typography>
                      </Grid>
                    )}
                    {caseItem.item.providerStatus && (
                      <Grid size={{ xs: 6, sm: 4 }}>
                        <Typography variant="caption" color="text.secondary">Provider Status</Typography>
                        <Typography variant="body2">{formatEnumLabel(caseItem.item.providerStatus)}</Typography>
                      </Grid>
                    )}
                    <Grid size={{ xs: 12 }}>
                      <Typography variant="caption" color="text.secondary">Discrepancy Description</Typography>
                      <Typography variant="body2" sx={{ whiteSpace: 'pre-wrap', wordBreak: 'break-word', mt: 0.5 }}>
                        {sanitizeOperatorDescription(caseItem.item.description)}
                      </Typography>
                    </Grid>
                  </Grid>
                </Paper>
              </Box>

              {isResolved && (
                <Box>
                  <Typography variant="subtitle2" color="success.main" gutterBottom>
                    Resolution Details
                  </Typography>
                  <Paper variant="outlined" sx={{ p: 2, bgcolor: 'success.50', borderColor: 'success.200' }}>
                    <Grid container spacing={1.5}>
                      <Grid size={{ xs: 6, sm: 4 }}>
                        <Typography variant="caption" color="text.secondary">Resolved At</Typography>
                        <Typography variant="body2">
                          {formatDateTime(caseItem.resolvedAt, 'N/A')}
                        </Typography>
                      </Grid>
                      <Grid size={{ xs: 6, sm: 4 }}>
                        <Typography variant="caption" color="text.secondary">Resolved By</Typography>
                        <Typography variant="body2" sx={{ fontFamily: 'monospace' }}>
                          {caseItem.resolvedByUserId ? caseItem.resolvedByUserId.slice(0, 8) + '...' : 'System'}
                        </Typography>
                      </Grid>
                      <Grid size={{ xs: 12, sm: 4 }}>
                        <Typography variant="caption" color="text.secondary">Resolution Action</Typography>
                        <Typography variant="body2" sx={{ fontWeight: 500 }}>
                          {formatEnumLabel(caseItem.resolutionAction || 'MANUAL_REVIEW_COMPLETED')}
                        </Typography>
                      </Grid>
                      <Grid size={{ xs: 12 }}>
                        <Typography variant="caption" color="text.secondary">Resolution Note</Typography>
                        <Typography variant="body2" sx={{ whiteSpace: 'pre-wrap', wordBreak: 'break-word', mt: 0.5 }}>
                          {caseItem.resolutionNote || 'No resolution note recorded.'}
                        </Typography>
                      </Grid>
                    </Grid>
                  </Paper>
                </Box>
              )}
            </Stack>
          )}
        </DialogContent>
        <DialogActions sx={{ px: 3, py: 2, justifyContent: 'space-between' }}>
          <Box>
            {caseItem && !isResolved && (
              <Stack direction="row" spacing={1}>
                <Button
                  variant="outlined"
                  size="small"
                  startIcon={<AssignmentIndIcon />}
                  onClick={handleClaim}
                  disabled={isAssignedToMe || isAssignedToOther || claimMutation.isPending}
                  title={
                    isAssignedToMe
                      ? 'Assigned to You'
                      : isAssignedToOther
                      ? 'Assigned to another operator'
                      : 'Claim Case'
                  }
                  aria-label={
                    isAssignedToMe
                      ? 'Claim case (Assigned to you)'
                      : isAssignedToOther
                      ? 'Claim case (Assigned to another operator)'
                      : 'Claim case'
                  }
                >
                  {isAssignedToMe ? 'Assigned to You' : 'Claim Case'}
                </Button>

                {isSnapshotMismatch && (
                  <Button
                    variant="contained"
                    color="secondary"
                    size="small"
                    startIcon={<BuildCircleOutlinedIcon />}
                    onClick={() => setRepairOpen(true)}
                    disabled={isAssignedToOther}
                    title={
                      isAssignedToOther
                        ? 'Assigned to another operator'
                        : 'Repair Snapshot'
                    }
                    aria-label={
                      isAssignedToOther
                        ? 'Repair snapshot (Assigned to another operator)'
                        : 'Repair snapshot'
                    }
                  >
                    Repair Snapshot
                  </Button>
                )}

                {!isSnapshotMismatch && (
                  <Button
                    variant="contained"
                    color="primary"
                    size="small"
                    startIcon={<CheckCircleIcon />}
                    onClick={() => setResolveOpen(true)}
                    disabled={!isAssignedToMe}
                    title={
                      isAssignedToOther
                        ? 'Assigned to another operator'
                        : isUnassigned
                        ? 'Claim case before resolving'
                        : 'Resolve Case'
                    }
                    aria-label={
                      isAssignedToOther
                        ? 'Resolve case (Assigned to another operator)'
                        : isUnassigned
                        ? 'Claim case before resolving'
                        : 'Resolve case'
                    }
                  >
                    Resolve Case
                  </Button>
                )}
              </Stack>
            )}
          </Box>
          <Button onClick={onClose} variant="outlined">
            Close
          </Button>
        </DialogActions>
      </Dialog>

      {caseItem && (
        <>
          <ResolveCaseDialog
            caseItem={caseItem}
            open={resolveOpen}
            onClose={() => setResolveOpen(false)}
          />
          <SnapshotRepairDialog
            caseItem={caseItem}
            open={repairOpen}
            onClose={() => setRepairOpen(false)}
          />
        </>
      )}
    </>
  );
};
