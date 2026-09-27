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
import BuildCircleOutlinedIcon from '@mui/icons-material/BuildCircleOutlined';
import CheckCircleIcon from '@mui/icons-material/CheckCircle';
import { StatusBadge } from '../../shared/components/StatusBadge';
import { CopyButton } from '../../shared/components/CopyButton';
import { useAuth } from '../../auth/hooks/useAuth';
import { useReconciliationCases } from '../hooks/useReconciliation';
import { ReconciliationCaseResponse } from '../types/reconciliation.types';
import { SnapshotRepairDialog } from './SnapshotRepairDialog';
import { formatMinorUnitsToInr } from '../../shared/utils/money';

export const SnapshotRepairView: React.FC = () => {
  const { user } = useAuth();
  const [page, setPage] = useState(0);
  const [rowsPerPage, setRowsPerPage] = useState(10);
  const [repairingCase, setRepairingCase] = useState<ReconciliationCaseResponse | null>(null);

  const { data, isLoading, isError } = useReconciliationCases({
    problemType: 'SNAPSHOT_MISMATCH',
    page,
    size: rowsPerPage,
  });

  return (
    <Box>
      <Alert severity="info" sx={{ mb: 3 }}>
        <Typography variant="subtitle2" sx={{ fontWeight: 600 }} gutterBottom>
          Case-Scoped Balance Snapshot Reconciliation
        </Typography>
        <Typography variant="body2">
          Balance snapshots are materialized caches that accelerate wallet queries. When an invariant check detects
          a discrepancy between a cached snapshot and the immutable journal entries, a case is recorded here.
          Executing a repair recalculates the exact ledger balance from all historical append-only journal entries.
          Snapshot repairs are strictly scoped to verified discrepancy cases.
        </Typography>
      </Alert>

      {isError && (
        <Alert severity="error" sx={{ mb: 2 }}>
          Failed to load snapshot repair cases.
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
                  <TableCell>Case ID</TableCell>
                  <TableCell>Account ID</TableCell>
                  <TableCell>Status</TableCell>
                  <TableCell align="right">Snapshot Cache</TableCell>
                  <TableCell align="right">Journal Ground Truth</TableCell>
                  <TableCell>Detected At</TableCell>
                  <TableCell align="center">Action</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {data?.items.length === 0 ? (
                  <TableRow>
                    <TableCell colSpan={7} align="center" sx={{ py: 5 }}>
                      <Stack spacing={1} sx={{ alignItems: 'center' }}>
                        <CheckCircleIcon color="success" sx={{ fontSize: 36 }} />
                        <Typography sx={{ fontWeight: 500 }}>All Balance Snapshots Consistent</Typography>
                        <Typography variant="body2" color="text.secondary">
                          No snapshot mismatch cases found. All ledger accounts match their journal history.
                        </Typography>
                      </Stack>
                    </TableCell>
                  </TableRow>
                ) : (
                  data?.items.map((caseItem) => {
                    const isAssignedToMe = caseItem.assignedToUserId === user?.id;
                    const isClaimantAllowed = caseItem.assignedToUserId === null || isAssignedToMe;
                    const isResolved = caseItem.status === 'RESOLVED';
                    const isEligibleStatus = caseItem.status === 'OPEN' || caseItem.status === 'IN_REVIEW';
                    const isSnapshotMismatch = caseItem.item.problemType === 'SNAPSHOT_MISMATCH';
                    const canRepair = isSnapshotMismatch && isEligibleStatus && isClaimantAllowed;

                    return (
                      <TableRow key={caseItem.id} hover>
                        <TableCell>
                          <Stack direction="row" spacing={0.5} sx={{ alignItems: 'center' }}>
                            <Typography variant="body2" sx={{ fontFamily: 'monospace' }}>
                              {caseItem.id.slice(0, 8)}...
                            </Typography>
                            <CopyButton value={caseItem.id} label="case ID" />
                          </Stack>
                        </TableCell>
                        <TableCell>
                          <Stack direction="row" spacing={0.5} sx={{ alignItems: 'center' }}>
                            <Typography variant="body2" sx={{ fontFamily: 'monospace' }}>
                              {caseItem.item.entityId}
                            </Typography>
                            <CopyButton value={caseItem.item.entityId} label="account ID" />
                          </Stack>
                        </TableCell>
                        <TableCell>
                          <StatusBadge status={caseItem.status} />
                        </TableCell>
                        <TableCell align="right">
                          <Typography
                            variant="body2"
                            sx={{ color: 'error.main', fontWeight: 500 }}
                          >
                            {caseItem.item.actualValue !== null
                              ? formatMinorUnitsToInr(caseItem.item.actualValue)
                              : 'N/A'}
                          </Typography>
                        </TableCell>
                        <TableCell align="right">
                          <Typography
                            variant="body2"
                            sx={{ color: 'success.main', fontWeight: 600 }}
                          >
                            {caseItem.item.expectedValue !== null
                              ? formatMinorUnitsToInr(caseItem.item.expectedValue)
                              : 'N/A'}
                          </Typography>
                        </TableCell>
                        <TableCell>
                          <Typography variant="caption">
                            {new Date(caseItem.openedAt).toLocaleString()}
                          </Typography>
                        </TableCell>
                        <TableCell align="center">
                          {isResolved ? (
                            <Typography variant="caption" sx={{ color: 'success.main', fontWeight: 500 }}>
                              Repaired
                            </Typography>
                          ) : canRepair ? (
                            <Button
                              size="small"
                              variant="contained"
                              color="secondary"
                              startIcon={<BuildCircleOutlinedIcon />}
                              onClick={() => setRepairingCase(caseItem)}
                            >
                              Repair
                            </Button>
                          ) : (
                            <Typography variant="caption" color="text.secondary">
                              {!isClaimantAllowed ? 'Assigned to another operator' : 'Ineligible'}
                            </Typography>
                          )}
                        </TableCell>
                      </TableRow>
                    );
                  })
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

      <SnapshotRepairDialog
        caseItem={repairingCase}
        open={Boolean(repairingCase)}
        onClose={() => setRepairingCase(null)}
      />
    </Box>
  );
};
