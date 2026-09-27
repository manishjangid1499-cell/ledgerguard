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
  Pagination,
  Paper,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Typography,
} from '@mui/material';
import { StatusBadge } from '../../shared/components/StatusBadge';
import { CopyButton } from '../../shared/components/CopyButton';
import { useReconciliationRun, useReconciliationRunItems } from '../hooks/useReconciliation';

interface RunDetailDialogProps {
  runId: string | null;
  open: boolean;
  onClose: () => void;
}

export const RunDetailDialog: React.FC<RunDetailDialogProps> = ({
  runId,
  open,
  onClose,
}) => {
  const [page, setPage] = useState(0);
  const size = 10;

  const { data: run, isLoading: isRunLoading, error: runError } = useReconciliationRun(
    runId || ''
  );
  const {
    data: itemsPage,
    isLoading: isItemsLoading,
    error: itemsError,
  } = useReconciliationRunItems(runId || '', page, size);

  if (!open) return null;

  return (
    <Dialog
      open={open}
      onClose={onClose}
      maxWidth="md"
      fullWidth
      aria-labelledby="run-detail-dialog-title"
    >
      <DialogTitle id="run-detail-dialog-title" sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center' }}>
          <span>Reconciliation Run Details</span>
          {run && <StatusBadge status={run.status} />}
        </Stack>
      </DialogTitle>
      <DialogContent dividers>
        {isRunLoading && (
          <Stack sx={{ alignItems: 'center', py: 4 }}>
            <CircularProgress />
          </Stack>
        )}

        {runError && (
          <Alert severity="error" sx={{ mb: 2 }}>
            Failed to load run details.
          </Alert>
        )}

        {run && (
          <Stack spacing={3}>
            {run.failureReason && (
              <Alert severity="error">
                <strong>Failure Reason:</strong> {run.failureReason}
              </Alert>
            )}

            <Grid container spacing={2}>
              <Grid size={{ xs: 12, sm: 6 }}>
                <Typography variant="caption" color="text.secondary">Run ID</Typography>
                <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
                  <Typography variant="body2" sx={{ fontFamily: 'monospace' }}>{run.id}</Typography>
                  <CopyButton value={run.id} label="run ID" />
                </Stack>
              </Grid>
              <Grid size={{ xs: 6, sm: 3 }}>
                <Typography variant="caption" color="text.secondary">Trigger Source</Typography>
                <Typography variant="body2" sx={{ fontWeight: 500 }}>{run.triggerSource}</Typography>
              </Grid>
              <Grid size={{ xs: 6, sm: 3 }}>
                <Typography variant="caption" color="text.secondary">Started At</Typography>
                <Typography variant="body2">
                  {new Date(run.startedAt).toLocaleString()}
                </Typography>
              </Grid>
              <Grid size={{ xs: 6, sm: 3 }}>
                <Typography variant="caption" color="text.secondary">Completed At</Typography>
                <Typography variant="body2">
                  {run.completedAt ? new Date(run.completedAt).toLocaleString() : 'In Progress'}
                </Typography>
              </Grid>
              <Grid size={{ xs: 6, sm: 3 }}>
                <Typography variant="caption" color="text.secondary">Journals Scanned</Typography>
                <Typography variant="body2">{run.journalsChecked.toLocaleString()}</Typography>
              </Grid>
              <Grid size={{ xs: 6, sm: 3 }}>
                <Typography variant="caption" color="text.secondary">Accounts Scanned</Typography>
                <Typography variant="body2">{run.accountsChecked.toLocaleString()}</Typography>
              </Grid>
              <Grid size={{ xs: 6, sm: 3 }}>
                <Typography variant="caption" color="text.secondary">Operations Scanned</Typography>
                <Typography variant="body2">{run.operationsChecked.toLocaleString()}</Typography>
              </Grid>
              <Grid size={{ xs: 6, sm: 3 }}>
                <Typography variant="caption" color="text.secondary">Discrepancies</Typography>
                <Typography variant="body2" sx={{ color: run.discrepancyCount > 0 ? 'error.main' : 'text.primary', fontWeight: run.discrepancyCount > 0 ? 600 : 400 }}>
                  {run.discrepancyCount}
                </Typography>
              </Grid>
              <Grid size={{ xs: 6, sm: 3 }}>
                <Typography variant="caption" color="text.secondary">Unresolved</Typography>
                <Typography variant="body2" sx={{ color: run.unresolvedCount > 0 ? 'warning.main' : 'text.primary' }}>
                  {run.unresolvedCount}
                </Typography>
              </Grid>
            </Grid>

            <Divider />

            <Box>
              <Typography variant="subtitle1" sx={{ fontWeight: 600 }} gutterBottom>
                Discrepancy & Observation Items ({itemsPage?.totalElements ?? 0})
              </Typography>

              {isItemsLoading && (
                <Stack sx={{ alignItems: 'center', py: 2 }}>
                  <CircularProgress size={24} />
                </Stack>
              )}

              {itemsError && (
                <Alert severity="error">Failed to load run discrepancy items.</Alert>
              )}

              {itemsPage && itemsPage.items.length === 0 && (
                <Typography variant="body2" color="text.secondary" sx={{ py: 2 }}>
                  No discrepancies or observations recorded for this run. Financial invariants verified.
                </Typography>
              )}

              {itemsPage && itemsPage.items.length > 0 && (
                <>
                  <TableContainer component={Paper} variant="outlined" sx={{ mt: 1 }}>
                    <Table size="small">
                      <TableHead>
                        <TableRow>
                          <TableCell>Problem Type</TableCell>
                          <TableCell>Classification</TableCell>
                          <TableCell>Level</TableCell>
                          <TableCell>Entity</TableCell>
                          <TableCell>Description</TableCell>
                          <TableCell>Detected</TableCell>
                        </TableRow>
                      </TableHead>
                      <TableBody>
                        {itemsPage.items.map((item) => (
                          <TableRow key={item.id}>
                            <TableCell>
                              <Typography variant="body2" sx={{ fontWeight: 500 }}>
                                {item.problemType.replaceAll('_', ' ')}
                              </Typography>
                            </TableCell>
                            <TableCell>
                              <StatusBadge status={item.classification} />
                            </TableCell>
                            <TableCell>
                              <Typography variant="caption">
                                {item.level.replaceAll('_', ' ')}
                              </Typography>
                            </TableCell>
                            <TableCell>
                              <Stack direction="row" spacing={0.5} sx={{ alignItems: 'center' }}>
                                <Typography variant="caption" sx={{ fontFamily: 'monospace' }}>
                                  {item.entityType}: {item.entityId.slice(0, 8)}...
                                </Typography>
                                <CopyButton value={item.entityId} label="entity ID" />
                              </Stack>
                            </TableCell>
                            <TableCell sx={{ maxWidth: 260 }}>
                              <Typography variant="caption" noWrap title={item.description}>
                                {item.description}
                              </Typography>
                            </TableCell>
                            <TableCell>
                              <Typography variant="caption">
                                {new Date(item.detectedAt).toLocaleTimeString()}
                              </Typography>
                            </TableCell>
                          </TableRow>
                        ))}
                      </TableBody>
                    </Table>
                  </TableContainer>

                  {itemsPage.totalPages > 1 && (
                    <Stack direction="row" sx={{ justifyContent: 'flex-end', mt: 2 }}>
                      <Pagination
                        count={itemsPage.totalPages}
                        page={page + 1}
                        onChange={(_, value) => setPage(value - 1)}
                        size="small"
                        color="primary"
                      />
                    </Stack>
                  )}
                </>
              )}
            </Box>
          </Stack>
        )}
      </DialogContent>
      <DialogActions sx={{ px: 3, py: 2 }}>
        <Button onClick={onClose} variant="outlined">
          Close
        </Button>
      </DialogActions>
    </Dialog>
  );
};
