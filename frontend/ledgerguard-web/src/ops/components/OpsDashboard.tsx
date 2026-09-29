import React, { useState } from 'react';
import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
  CircularProgress,
  Grid,
  Paper,
  Stack,
  Typography,
} from '@mui/material';
import { useNavigate } from 'react-router-dom';
import PlayArrowIcon from '@mui/icons-material/PlayArrow';
import HistoryOutlinedIcon from '@mui/icons-material/HistoryOutlined';
import AssignmentOutlinedIcon from '@mui/icons-material/AssignmentOutlined';
import WarningAmberOutlinedIcon from '@mui/icons-material/WarningAmberOutlined';
import CheckCircleIcon from '@mui/icons-material/CheckCircle';
import VisibilityOutlinedIcon from '@mui/icons-material/VisibilityOutlined';
import RefreshIcon from '@mui/icons-material/Refresh';
import { StatusBadge } from '../../shared/components/StatusBadge';
import { useReconciliationSummary } from '../../reconciliation/hooks/useReconciliation';
import { RunTriggerDialog } from '../../reconciliation/components/RunTriggerDialog';
import { RunDetailDialog } from '../../reconciliation/components/RunDetailDialog';
import { formatDateTime } from '../../shared/utils/display';

export const OpsDashboard: React.FC = () => {
  const navigate = useNavigate();
  const {
    data: summary,
    isLoading,
    isError,
    refetch,
    isFetching,
    dataUpdatedAt,
  } = useReconciliationSummary();

  const [isTriggerOpen, setIsTriggerOpen] = useState(false);
  const [selectedRunId, setSelectedRunId] = useState<string | null>(null);

  const formatDuration = (start: string, end: string | null) => {
    if (!end) return 'Running...';
    const durationMs = new Date(end).getTime() - new Date(start).getTime();
    if (durationMs < 1000) return `${durationMs} ms`;
    return `${(durationMs / 1000).toFixed(1)} s`;
  };

  const handleCardKeyDown = (e: React.KeyboardEvent, path: string) => {
    if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault();
      navigate(path);
    }
  };

  return (
    <Box>
      <Stack
        spacing={2}
        sx={{
          flexDirection: { xs: 'column', sm: 'row' },
          justifyContent: 'space-between',
          alignItems: { xs: 'flex-start', sm: 'center' },
          mb: 3,
        }}
      >
        <Box>
          <Typography
            variant="h4"
            component="h1"
            color="primary.main"
            sx={{ fontSize: { xs: '1.75rem', md: '2rem' }, mb: 0.5, fontWeight: 700 }}
          >
            Operations Command Center
          </Typography>
          <Typography variant="body2" color="text.secondary">
            Continuous payment integrity monitoring, double-entry verification, and case resolution.
          </Typography>
          {dataUpdatedAt > 0 && (
            <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 0.5 }}>
              Last updated: {formatDateTime(dataUpdatedAt)}
            </Typography>
          )}
        </Box>

        <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
          <Button
            variant="outlined"
            size="medium"
            aria-label="Refresh metrics"
            startIcon={
              isFetching && !isLoading ? (
                <CircularProgress size={16} color="inherit" />
              ) : (
                <RefreshIcon />
              )
            }
            onClick={() => refetch()}
            disabled={isFetching}
          >
            {isFetching && !isLoading ? 'Refreshing...' : 'Refresh'}
          </Button>

          <Button
            variant="contained"
            color="primary"
            startIcon={<PlayArrowIcon />}
            onClick={() => setIsTriggerOpen(true)}
          >
            Start Reconciliation
          </Button>
        </Stack>
      </Stack>

      {isError && (
        <Alert
          severity="error"
          sx={{ mb: 3 }}
          action={
            <Button color="inherit" size="small" onClick={() => refetch()}>
              Retry
            </Button>
          }
        >
          Failed to load operations summary metrics.
        </Alert>
      )}

      {isLoading ? (
        <Stack sx={{ alignItems: 'center', py: 8 }}>
          <CircularProgress />
        </Stack>
      ) : (
        <Stack spacing={3}>
          {/* Key Metrics Cards */}
          <Grid container spacing={2}>
            <Grid size={{ xs: 12, sm: 6, md: 3 }}>
              <Card
                variant="outlined"
                role="button"
                tabIndex={0}
                aria-label="View open cases"
                onClick={() => navigate('/app/reconciliation?tab=cases&status=OPEN')}
                onKeyDown={(e) => handleCardKeyDown(e, '/app/reconciliation?tab=cases&status=OPEN')}
                sx={{
                  cursor: 'pointer',
                  transition: 'all 0.15s ease-in-out',
                  '&:hover': { borderColor: 'primary.main', bgcolor: 'action.hover' },
                  '&:focus-visible': { outline: '2px solid', outlineColor: 'primary.main' },
                }}
              >
                <CardContent>
                  <Stack direction="row" sx={{ justifyContent: 'space-between', alignItems: 'center' }}>
                    <Typography variant="caption" color="text.secondary" sx={{ fontWeight: 600 }}>
                      OPEN CASES
                    </Typography>
                    <WarningAmberOutlinedIcon color={summary?.openCases ? 'warning' : 'action'} />
                  </Stack>
                  <Typography variant="h4" sx={{ fontWeight: 700, mt: 1 }}>
                    {summary?.openCases ?? 0}
                  </Typography>
                  <Typography variant="caption" color="text.secondary">
                    Requiring operator triage
                  </Typography>
                </CardContent>
              </Card>
            </Grid>

            <Grid size={{ xs: 12, sm: 6, md: 3 }}>
              <Card
                variant="outlined"
                role="button"
                tabIndex={0}
                aria-label="View in review cases"
                onClick={() => navigate('/app/reconciliation?tab=cases&status=IN_REVIEW')}
                onKeyDown={(e) => handleCardKeyDown(e, '/app/reconciliation?tab=cases&status=IN_REVIEW')}
                sx={{
                  cursor: 'pointer',
                  transition: 'all 0.15s ease-in-out',
                  '&:hover': { borderColor: 'primary.main', bgcolor: 'action.hover' },
                  '&:focus-visible': { outline: '2px solid', outlineColor: 'primary.main' },
                }}
              >
                <CardContent>
                  <Stack direction="row" sx={{ justifyContent: 'space-between', alignItems: 'center' }}>
                    <Typography variant="caption" color="text.secondary" sx={{ fontWeight: 600 }}>
                      IN REVIEW CASES
                    </Typography>
                    <AssignmentOutlinedIcon color="primary" />
                  </Stack>
                  <Typography variant="h4" sx={{ fontWeight: 700, mt: 1 }}>
                    {summary?.inReviewCases ?? 0}
                  </Typography>
                  <Typography variant="caption" color="text.secondary">
                    Assigned to operators
                  </Typography>
                </CardContent>
              </Card>
            </Grid>

            <Grid size={{ xs: 12, sm: 6, md: 3 }}>
              <Card
                variant="outlined"
                role="button"
                tabIndex={0}
                aria-label="View resolved cases"
                onClick={() => navigate('/app/reconciliation?tab=cases&status=RESOLVED')}
                onKeyDown={(e) => handleCardKeyDown(e, '/app/reconciliation?tab=cases&status=RESOLVED')}
                sx={{
                  cursor: 'pointer',
                  transition: 'all 0.15s ease-in-out',
                  '&:hover': { borderColor: 'primary.main', bgcolor: 'action.hover' },
                  '&:focus-visible': { outline: '2px solid', outlineColor: 'primary.main' },
                }}
              >
                <CardContent>
                  <Stack direction="row" sx={{ justifyContent: 'space-between', alignItems: 'center' }}>
                    <Typography variant="caption" color="text.secondary" sx={{ fontWeight: 600 }}>
                      RESOLVED CASES
                    </Typography>
                    <CheckCircleIcon color="success" />
                  </Stack>
                  <Typography variant="h4" sx={{ fontWeight: 700, mt: 1 }}>
                    {summary?.resolvedCases ?? 0}
                  </Typography>
                  <Typography variant="caption" color="text.secondary">
                    Successfully remediated
                  </Typography>
                </CardContent>
              </Card>
            </Grid>

            <Grid size={{ xs: 12, sm: 6, md: 3 }}>
              <Card
                variant="outlined"
                role="button"
                tabIndex={0}
                aria-label="View reconciliation runs"
                onClick={() => navigate('/app/reconciliation?tab=runs')}
                onKeyDown={(e) => handleCardKeyDown(e, '/app/reconciliation?tab=runs')}
                sx={{
                  cursor: 'pointer',
                  transition: 'all 0.15s ease-in-out',
                  '&:hover': { borderColor: 'primary.main', bgcolor: 'action.hover' },
                  '&:focus-visible': { outline: '2px solid', outlineColor: 'primary.main' },
                }}
              >
                <CardContent>
                  <Stack direction="row" sx={{ justifyContent: 'space-between', alignItems: 'center' }}>
                    <Typography variant="caption" color="text.secondary" sx={{ fontWeight: 600 }}>
                      TOTAL RECONCILIATION RUNS
                    </Typography>
                    <HistoryOutlinedIcon color="action" />
                  </Stack>
                  <Typography variant="h4" sx={{ fontWeight: 700, mt: 1 }}>
                    {summary?.totalRuns ?? 0}
                  </Typography>
                  <Typography variant="caption" color="text.secondary">
                    Automated & manual sweeps
                  </Typography>
                </CardContent>
              </Card>
            </Grid>
          </Grid>

          {/* Latest Run Status Panel */}
          <Paper variant="outlined" sx={{ p: 3 }}>
            <Stack direction="row" sx={{ justifyContent: 'space-between', alignItems: 'center', mb: 2 }}>
              <Typography variant="h6" sx={{ fontWeight: 600 }}>
                Latest Reconciliation Execution
              </Typography>
              {summary?.latestRun && (
                <Button
                  size="small"
                  variant="outlined"
                  startIcon={<VisibilityOutlinedIcon />}
                  onClick={() => setSelectedRunId(summary.latestRun!.id)}
                >
                  View Run Details
                </Button>
              )}
            </Stack>

            {!summary?.latestRun ? (
              <Typography variant="body2" color="text.secondary">
                No reconciliation runs recorded. Trigger a run to verify ledger invariants.
              </Typography>
            ) : (
              <Grid container spacing={2}>
                <Grid size={{ xs: 12, sm: 6, md: 3 }}>
                  <Typography variant="caption" color="text.secondary">Status</Typography>
                  <Box sx={{ mt: 0.5 }}>
                    <StatusBadge status={summary.latestRun.status} />
                  </Box>
                </Grid>
                <Grid size={{ xs: 12, sm: 6, md: 3 }}>
                  <Typography variant="caption" color="text.secondary">Triggered At</Typography>
                  <Typography variant="body2" sx={{ fontWeight: 500 }}>
                    {formatDateTime(summary.latestRun.startedAt)}
                  </Typography>
                </Grid>
                <Grid size={{ xs: 6, sm: 3, md: 2 }}>
                  <Typography variant="caption" color="text.secondary">Duration</Typography>
                  <Typography variant="body2">
                    {formatDuration(summary.latestRun.startedAt, summary.latestRun.completedAt)}
                  </Typography>
                </Grid>
                <Grid size={{ xs: 6, sm: 3, md: 2 }}>
                  <Typography variant="caption" color="text.secondary">Checks Evaluated</Typography>
                  <Typography variant="body2" sx={{ fontWeight: 600 }}>
                    {(
                      summary.latestRun.journalsChecked +
                      summary.latestRun.accountsChecked +
                      summary.latestRun.operationsChecked
                    ).toLocaleString()}
                  </Typography>
                </Grid>
                <Grid size={{ xs: 6, sm: 3, md: 2 }}>
                  <Typography variant="caption" color="text.secondary">Discrepancies</Typography>
                  <Typography
                    variant="body2"
                    sx={{
                      fontWeight: 600,
                      color: summary.latestRun.discrepancyCount > 0 ? 'error.main' : 'success.main',
                    }}
                  >
                    {summary.latestRun.discrepancyCount}
                  </Typography>
                </Grid>
              </Grid>
            )}
          </Paper>

          {/* Quick Actions Panel */}
          <Paper variant="outlined" sx={{ p: 3 }}>
            <Typography variant="h6" sx={{ fontWeight: 600, mb: 2 }}>
              Operator Quick Actions
            </Typography>
            <Grid container spacing={2}>
              <Grid size={{ xs: 12, sm: 4 }}>
                <Button
                  fullWidth
                  variant="outlined"
                  size="large"
                  startIcon={<PlayArrowIcon />}
                  onClick={() => setIsTriggerOpen(true)}
                  sx={{ py: 1.5, justifyContent: 'flex-start', px: 2 }}
                >
                  <Box sx={{ textAlign: 'left' }}>
                    <Typography variant="subtitle2">Start Reconciliation</Typography>
                    <Typography variant="caption" color="text.secondary">
                      Execute synchronous ledger & PSP integrity sweep
                    </Typography>
                  </Box>
                </Button>
              </Grid>

              <Grid size={{ xs: 12, sm: 4 }}>
                <Button
                  fullWidth
                  variant="outlined"
                  size="large"
                  startIcon={<HistoryOutlinedIcon />}
                  onClick={() => navigate('/app/reconciliation?tab=runs')}
                  sx={{ py: 1.5, justifyContent: 'flex-start', px: 2 }}
                >
                  <Box sx={{ textAlign: 'left' }}>
                    <Typography variant="subtitle2">Review Runs</Typography>
                    <Typography variant="caption" color="text.secondary">
                      Inspect historical run reports and check counts
                    </Typography>
                  </Box>
                </Button>
              </Grid>

              <Grid size={{ xs: 12, sm: 4 }}>
                <Button
                  fullWidth
                  variant="outlined"
                  size="large"
                  startIcon={<AssignmentOutlinedIcon />}
                  onClick={() => navigate('/app/reconciliation?tab=cases')}
                  sx={{ py: 1.5, justifyContent: 'flex-start', px: 2 }}
                >
                  <Box sx={{ textAlign: 'left' }}>
                    <Typography variant="subtitle2">Review Cases</Typography>
                    <Typography variant="caption" color="text.secondary">
                      Triage, assign, and manually resolve open discrepancies
                    </Typography>
                  </Box>
                </Button>
              </Grid>
            </Grid>
          </Paper>
        </Stack>
      )}

      <RunTriggerDialog
        open={isTriggerOpen}
        onClose={() => setIsTriggerOpen(false)}
        onSuccess={(run) => {
          if (run) setSelectedRunId(run.id);
        }}
      />

      <RunDetailDialog
        runId={selectedRunId}
        open={Boolean(selectedRunId)}
        onClose={() => setSelectedRunId(null)}
      />
    </Box>
  );
};
