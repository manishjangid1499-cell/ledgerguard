import React, { useState } from 'react';
import {
  Alert,
  Box,
  Button,
  CircularProgress,
  FormControl,
  Grid,
  InputLabel,
  MenuItem,
  Paper,
  Select,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TablePagination,
  TableRow,
  TextField,
  Typography,
} from '@mui/material';
import { useSearchParams } from 'react-router-dom';
import FilterAltOffIcon from '@mui/icons-material/FilterAltOff';
import AssignmentIndIcon from '@mui/icons-material/AssignmentInd';
import CheckCircleIcon from '@mui/icons-material/CheckCircle';
import BuildCircleOutlinedIcon from '@mui/icons-material/BuildCircleOutlined';
import VisibilityOutlinedIcon from '@mui/icons-material/VisibilityOutlined';
import { StatusBadge } from '../../shared/components/StatusBadge';
import { CopyButton } from '../../shared/components/CopyButton';
import { useAuth } from '../../auth/hooks/useAuth';
import {
  CaseFilterParams,
  ReconciliationCaseResponse,
  ReconciliationCaseStatus,
  ReconciliationLevel,
  ReconciliationProblemType,
} from '../types/reconciliation.types';
import { useClaimCase, useReconciliationCases } from '../hooks/useReconciliation';
import { CaseDetailDialog } from './CaseDetailDialog';
import { ResolveCaseDialog } from './ResolveCaseDialog';
import { SnapshotRepairDialog } from './SnapshotRepairDialog';
import {
  formatDateTime,
  formatEnumLabel,
} from '../../shared/utils/display';

interface CasesViewProps {
  initialRunId?: string;
  initialProblemType?: ReconciliationProblemType;
}

export const CasesView: React.FC<CasesViewProps> = ({
  initialRunId,
  initialProblemType,
}) => {
  const { user } = useAuth();
  const [searchParams, setSearchParams] = useSearchParams();

  const statusFilter = (searchParams.get('status') as ReconciliationCaseStatus) || '';
  const levelFilter = (searchParams.get('level') as ReconciliationLevel) || '';
  const problemTypeFilter =
    (searchParams.get('problemType') as ReconciliationProblemType) || initialProblemType || '';
  const assignedFilter = (searchParams.get('assigned') as 'all' | 'assigned' | 'unassigned') || 'all';
  const runIdFilter = searchParams.get('runId') || initialRunId || '';
  const page = parseInt(searchParams.get('page') || '0', 10);
  const [rowsPerPage, setRowsPerPage] = useState(10);

  // Dialog states
  const [selectedCaseId, setSelectedCaseId] = useState<string | null>(null);
  const [resolvingCase, setResolvingCase] = useState<ReconciliationCaseResponse | null>(null);
  const [repairingCase, setRepairingCase] = useState<ReconciliationCaseResponse | null>(null);

  const claimMutation = useClaimCase();

  const updateParam = (key: string, value: string | undefined, resetPage = true) => {
    const next = new URLSearchParams(searchParams);
    if (!next.has('tab')) next.set('tab', 'cases');
    if (value && value !== 'all') {
      next.set(key, value);
    } else {
      next.delete(key);
    }
    if (resetPage) {
      next.delete('page');
    }
    setSearchParams(next);
  };

  const handleResetFilters = () => {
    const next = new URLSearchParams();
    next.set('tab', 'cases');
    setSearchParams(next);
  };

  const filterParams: CaseFilterParams = {
    page,
    size: rowsPerPage,
    ...(statusFilter ? { status: statusFilter } : {}),
    ...(levelFilter ? { level: levelFilter } : {}),
    ...(problemTypeFilter ? { problemType: problemTypeFilter } : {}),
    ...(assignedFilter === 'assigned' ? { assigned: true } : {}),
    ...(assignedFilter === 'unassigned' ? { assigned: false } : {}),
    ...(runIdFilter.trim() ? { runId: runIdFilter.trim() } : {}),
  };

  const { data, isLoading, isError } = useReconciliationCases(filterParams);

  const handleClaim = async (caseItem: ReconciliationCaseResponse) => {
    try {
      await claimMutation.mutateAsync(caseItem.id);
    } catch {
      // captured in claimMutation.error
    }
  };

  return (
    <Box>
      <Paper variant="outlined" sx={{ p: 2, mb: 3 }}>
        <Grid container spacing={2} sx={{ alignItems: 'center' }}>
          <Grid size={{ xs: 12, sm: 6, md: 2.4 }}>
            <FormControl fullWidth size="small">
              <InputLabel id="case-status-label">Status</InputLabel>
              <Select
                labelId="case-status-label"
                value={statusFilter}
                label="Status"
                onChange={(e) => updateParam('status', e.target.value as string)}
              >
                <MenuItem value="">All Statuses</MenuItem>
                <MenuItem value="OPEN">Open</MenuItem>
                <MenuItem value="IN_REVIEW">In Review</MenuItem>
                <MenuItem value="RESOLVED">Resolved</MenuItem>
              </Select>
            </FormControl>
          </Grid>

          <Grid size={{ xs: 12, sm: 6, md: 2.4 }}>
            <FormControl fullWidth size="small">
              <InputLabel id="case-level-label">Check Level</InputLabel>
              <Select
                labelId="case-level-label"
                value={levelFilter}
                label="Check Level"
                onChange={(e) => updateParam('level', e.target.value as string)}
              >
                <MenuItem value="">All Levels</MenuItem>
                <MenuItem value="JOURNAL_BALANCE">Journal Balance</MenuItem>
                <MenuItem value="SNAPSHOT_CONSISTENCY">Snapshot Consistency</MenuItem>
                <MenuItem value="PROVIDER_SETTLEMENT">Provider Settlement</MenuItem>
              </Select>
            </FormControl>
          </Grid>

          <Grid size={{ xs: 12, sm: 6, md: 2.4 }}>
            <FormControl fullWidth size="small">
              <InputLabel id="case-problem-type-label">Problem Type</InputLabel>
              <Select
                labelId="case-problem-type-label"
                value={problemTypeFilter}
                label="Problem Type"
                onChange={(e) => updateParam('problemType', e.target.value as string)}
              >
                <MenuItem value="">All Problems</MenuItem>
                <MenuItem value="UNBALANCED_JOURNAL">Unbalanced Journal</MenuItem>
                <MenuItem value="MALFORMED_JOURNAL">Malformed Journal</MenuItem>
                <MenuItem value="SNAPSHOT_MISMATCH">Snapshot Mismatch</MenuItem>
                <MenuItem value="SNAPSHOT_MISSING">Snapshot Missing</MenuItem>
                <MenuItem value="PROVIDER_STATUS_MISMATCH">Provider Status Mismatch</MenuItem>
                <MenuItem value="PROVIDER_IDENTITY_MISMATCH">Provider Identity Mismatch</MenuItem>
                <MenuItem value="PROVIDER_NOT_FOUND">Provider Not Found</MenuItem>
                <MenuItem value="PROVIDER_UNAVAILABLE">Provider Unavailable</MenuItem>
                <MenuItem value="PROVIDER_STILL_PROCESSING">Provider Still Processing</MenuItem>
              </Select>
            </FormControl>
          </Grid>

          <Grid size={{ xs: 12, sm: 6, md: 2.4 }}>
            <FormControl fullWidth size="small">
              <InputLabel id="case-assigned-label">Assignment</InputLabel>
              <Select
                labelId="case-assigned-label"
                value={assignedFilter}
                label="Assignment"
                onChange={(e) => updateParam('assigned', e.target.value as string)}
              >
                <MenuItem value="all">All Cases</MenuItem>
                <MenuItem value="assigned">Assigned</MenuItem>
                <MenuItem value="unassigned">Unassigned</MenuItem>
              </Select>
            </FormControl>
          </Grid>

          <Grid size={{ xs: 12, sm: 8, md: 2 }}>
            <TextField
              size="small"
              fullWidth
              label="Run ID"
              placeholder="Filter by Run ID"
              value={runIdFilter}
              onChange={(e) => updateParam('runId', e.target.value)}
            />
          </Grid>

          <Grid size={{ xs: 12, sm: 4, md: 0.4 }}>
            <Button
              variant="text"
              color="inherit"
              onClick={handleResetFilters}
              title="Clear filters"
              aria-label="Clear filters"
              sx={{ minWidth: 40, p: 1 }}
            >
              <FilterAltOffIcon />
            </Button>
          </Grid>
        </Grid>
      </Paper>

      {isError && (
        <Alert severity="error" sx={{ mb: 2 }}>
          Failed to load reconciliation cases.
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
                  <TableCell>Status</TableCell>
                  <TableCell>Problem Type</TableCell>
                  <TableCell>Level</TableCell>
                  <TableCell>Affected Entity</TableCell>
                  <TableCell>Assigned</TableCell>
                  <TableCell>Opened</TableCell>
                  <TableCell align="center">Actions</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {data?.items.length === 0 ? (
                  <TableRow>
                    <TableCell colSpan={8} align="center" sx={{ py: 4 }}>
                      <Typography color="text.secondary">
                        No reconciliation cases matching the current filters.
                      </Typography>
                    </TableCell>
                  </TableRow>
                ) : (
                  data?.items.map((caseItem) => {
                    const isAssignedToMe = caseItem.assignedToUserId === user?.id;
                    const isAssignedToOther =
                      caseItem.assignedToUserId !== null && !isAssignedToMe;
                    const isUnassigned = caseItem.assignedToUserId === null;
                    const isResolved = caseItem.status === 'RESOLVED';
                    const isSnapshotMismatch = caseItem.item.problemType === 'SNAPSHOT_MISMATCH';
                    const canRepairSnapshot =
                      isSnapshotMismatch && !isResolved && (isUnassigned || isAssignedToMe);

                    return (
                      <TableRow key={caseItem.id} hover>
                        <TableCell>
                          <Stack direction="row" spacing={0.5} sx={{ alignItems: 'center' }}>
                            <Typography
                              variant="body2"
                              sx={{
                                fontFamily: 'monospace',
                                cursor: 'pointer',
                                color: 'primary.main',
                                textDecoration: 'underline',
                              }}
                              onClick={() => setSelectedCaseId(caseItem.id)}
                            >
                              {caseItem.id.slice(0, 8)}...
                            </Typography>
                            <CopyButton value={caseItem.id} label={`case ID ${caseItem.id}`} />
                          </Stack>
                        </TableCell>
                        <TableCell>
                          <StatusBadge status={caseItem.status} />
                        </TableCell>
                        <TableCell>
                          <Typography variant="body2" sx={{ fontWeight: 500 }}>
                            {formatEnumLabel(caseItem.item.problemType)}
                          </Typography>
                        </TableCell>
                        <TableCell>
                          <Typography variant="caption" color="text.secondary">
                            {formatEnumLabel(caseItem.item.level)}
                          </Typography>
                        </TableCell>
                        <TableCell>
                          <Typography variant="caption" sx={{ fontFamily: 'monospace' }}>
                            {formatEnumLabel(caseItem.item.entityType)}: {caseItem.item.entityId.slice(0, 8)}...
                          </Typography>
                        </TableCell>
                        <TableCell>
                          <Typography variant="body2">
                            {caseItem.assignedToUserId ? (
                              isAssignedToMe ? (
                                <strong>You</strong>
                              ) : (
                                <span style={{ fontFamily: 'monospace' }}>
                                  {caseItem.assignedToUserId.slice(0, 8)}...
                                </span>
                              )
                            ) : (
                              <em style={{ color: 'gray' }}>Unassigned</em>
                            )}
                          </Typography>
                        </TableCell>
                        <TableCell>
                          <Typography variant="caption">
                            {formatDateTime(caseItem.openedAt)}
                          </Typography>
                        </TableCell>
                        <TableCell align="center">
                          <Stack direction="row" spacing={0.5} sx={{ justifyContent: 'center' }}>
                            <Button
                              size="small"
                              variant="outlined"
                              onClick={() => setSelectedCaseId(caseItem.id)}
                              title="View Details"
                              aria-label={`View details for case ${caseItem.id.slice(0, 8)}`}
                              sx={{ minWidth: 32, p: 0.5 }}
                            >
                              <VisibilityOutlinedIcon fontSize="small" />
                            </Button>

                            {!isResolved && (
                              <>
                                <Button
                                  size="small"
                                  variant="outlined"
                                  disabled={isAssignedToMe || isAssignedToOther || claimMutation.isPending}
                                  onClick={() => handleClaim(caseItem)}
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
                                  sx={{ minWidth: 32, p: 0.5 }}
                                >
                                  <AssignmentIndIcon fontSize="small" />
                                </Button>

                                {canRepairSnapshot && (
                                  <Button
                                    size="small"
                                    variant="outlined"
                                    color="secondary"
                                    onClick={() => setRepairingCase(caseItem)}
                                    title="Repair Snapshot"
                                    aria-label="Repair Snapshot"
                                    sx={{ minWidth: 32, p: 0.5 }}
                                  >
                                    <BuildCircleOutlinedIcon fontSize="small" />
                                  </Button>
                                )}

                                {!isSnapshotMismatch && (
                                  <Button
                                    size="small"
                                    variant="outlined"
                                    color="primary"
                                    disabled={!isAssignedToMe}
                                    onClick={() => setResolvingCase(caseItem)}
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
                                    sx={{ minWidth: 32, p: 0.5 }}
                                  >
                                    <CheckCircleIcon fontSize="small" />
                                  </Button>
                                )}
                              </>
                            )}
                          </Stack>
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
            onPageChange={(_, newPage) => updateParam('page', newPage > 0 ? String(newPage) : undefined, false)}
            rowsPerPage={rowsPerPage}
            onRowsPerPageChange={(e) => {
              setRowsPerPage(parseInt(e.target.value, 10));
              updateParam('page', undefined, false);
            }}
            rowsPerPageOptions={[5, 10, 25, 50]}
          />
        </Paper>
      )}

      <CaseDetailDialog
        caseId={selectedCaseId}
        open={Boolean(selectedCaseId)}
        onClose={() => setSelectedCaseId(null)}
      />

      <ResolveCaseDialog
        caseItem={resolvingCase}
        open={Boolean(resolvingCase)}
        onClose={() => setResolvingCase(null)}
      />

      <SnapshotRepairDialog
        caseItem={repairingCase}
        open={Boolean(repairingCase)}
        onClose={() => setRepairingCase(null)}
      />
    </Box>
  );
};
