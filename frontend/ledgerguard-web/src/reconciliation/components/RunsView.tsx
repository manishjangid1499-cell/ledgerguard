import React, { useState } from 'react';
import {
  Alert,
  Box,
  Button,
  CircularProgress,
  Paper,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TablePagination,
  TableRow,
  Typography,
} from '@mui/material';
import VisibilityOutlinedIcon from '@mui/icons-material/VisibilityOutlined';
import { StatusBadge } from '../../shared/components/StatusBadge';
import { CopyButton } from '../../shared/components/CopyButton';
import { useReconciliationRuns } from '../hooks/useReconciliation';
import { RunDetailDialog } from './RunDetailDialog';
import { formatDateTime, formatEnumLabel } from '../../shared/utils/display';

export const RunsView: React.FC = () => {
  const [page, setPage] = useState(0);
  const [rowsPerPage, setRowsPerPage] = useState(10);
  const [selectedRunId, setSelectedRunId] = useState<string | null>(null);

  const { data, isLoading, isError } = useReconciliationRuns(page, rowsPerPage);

  const formatDuration = (start: string, end: string | null) => {
    if (!end) return 'Running...';
    const durationMs = new Date(end).getTime() - new Date(start).getTime();
    if (durationMs < 1000) return `${durationMs} ms`;
    return `${(durationMs / 1000).toFixed(1)} s`;
  };

  return (
    <Box>
      <Stack direction="row" sx={{ justifyContent: 'space-between', alignItems: 'center', mb: 2 }}>
        <Typography variant="h6">Reconciliation Runs History</Typography>
      </Stack>

      {isError && (
        <Alert severity="error" sx={{ mb: 2 }}>
          Failed to load reconciliation runs.
        </Alert>
      )}

      {isLoading ? (
        <Stack sx={{ alignItems: 'center', py: 6 }}>
          <CircularProgress />
        </Stack>
      ) : (
        <Paper variant="outlined">
          <TableContainer>
            <Table size="medium">
              <TableHead>
                <TableRow>
                  <TableCell>Run ID</TableCell>
                  <TableCell>Status</TableCell>
                  <TableCell>Trigger</TableCell>
                  <TableCell>Started At</TableCell>
                  <TableCell>Duration</TableCell>
                  <TableCell align="right">Checks</TableCell>
                  <TableCell align="right">Discrepancies</TableCell>
                  <TableCell align="center">Action</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {data?.items.length === 0 ? (
                  <TableRow>
                    <TableCell colSpan={8} align="center" sx={{ py: 4 }}>
                      <Typography color="text.secondary">
                        No reconciliation runs recorded yet. Start one to audit ledger integrity.
                      </Typography>
                    </TableCell>
                  </TableRow>
                ) : (
                  data?.items.map((run) => (
                    <TableRow key={run.id} hover>
                      <TableCell>
                        <Stack direction="row" spacing={0.5} sx={{ alignItems: 'center' }}>
                          <Typography
                            variant="body2"
                            sx={{ fontFamily: 'monospace', cursor: 'pointer', color: 'primary.main', textDecoration: 'underline' }}
                            onClick={() => setSelectedRunId(run.id)}
                          >
                            {run.id.slice(0, 8)}...
                          </Typography>
                          <CopyButton value={run.id} label="run ID" />
                        </Stack>
                      </TableCell>
                      <TableCell>
                        <StatusBadge status={run.status} />
                      </TableCell>
                      <TableCell>
                        <Typography variant="body2">{formatEnumLabel(run.triggerSource)}</Typography>
                      </TableCell>
                      <TableCell>
                        <Typography variant="body2">
                          {formatDateTime(run.startedAt)}
                        </Typography>
                      </TableCell>
                      <TableCell>
                        <Typography variant="body2">
                          {formatDuration(run.startedAt, run.completedAt)}
                        </Typography>
                      </TableCell>
                      <TableCell align="right">
                        <Typography variant="body2">
                          {(run.journalsChecked + run.accountsChecked + run.operationsChecked).toLocaleString()}
                        </Typography>
                      </TableCell>
                      <TableCell align="right">
                        <Typography
                          variant="body2"
                          sx={{
                            fontWeight: run.discrepancyCount > 0 ? 600 : 400,
                            color: run.discrepancyCount > 0 ? 'error.main' : 'text.primary',
                          }}
                        >
                          {run.discrepancyCount}
                        </Typography>
                      </TableCell>
                      <TableCell align="center">
                        <Button
                          size="small"
                          variant="outlined"
                          startIcon={<VisibilityOutlinedIcon />}
                          onClick={() => setSelectedRunId(run.id)}
                          aria-label={`View details for run ${run.id.slice(0, 8)}`}
                        >
                          Details
                        </Button>
                      </TableCell>
                    </TableRow>
                  ))
                )}
              </TableBody>
            </Table>
          </TableContainer>

          <TablePagination
            component="div"
            count={data?.totalElements ?? 0}
            page={page}
            onPageChange={(_, newPage) => setPage(newPage)}
            rowsPerPage={rowsPerPage}
            onRowsPerPageChange={(e) => {
              setRowsPerPage(parseInt(e.target.value, 10));
              setPage(0);
            }}
            rowsPerPageOptions={[5, 10, 25, 50]}
          />
        </Paper>
      )}

      <RunDetailDialog
        runId={selectedRunId}
        open={Boolean(selectedRunId)}
        onClose={() => setSelectedRunId(null)}
      />
    </Box>
  );
};
