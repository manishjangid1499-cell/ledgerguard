import React, { useEffect, useState } from 'react';
import {
  Alert,
  Box,
  Card,
  CardContent,
  Chip,
  IconButton,
  Stack,
  Tab,
  Tabs,
  Tooltip,
  Typography,
} from '@mui/material';
import ContentCopyIcon from '@mui/icons-material/ContentCopy';
import CheckIcon from '@mui/icons-material/Check';
import TimerIcon from '@mui/icons-material/Timer';
import { LabRunView, ScenarioStatus, getApplicableInvariants } from '../types/failureLab.types';
import { RunTimeline } from './RunTimeline';
import { InvariantReportCards } from './InvariantReportCards';

interface Props {
  run: LabRunView;
}

function getStatusChipColor(status: ScenarioStatus): 'default' | 'primary' | 'success' | 'error' | 'warning' {
  switch (status) {
    case 'PENDING':
    case 'RUNNING':
      return 'primary';
    case 'PASSED':
      return 'success';
    case 'FAILED':
      return 'error';
    case 'TIMED_OUT':
      return 'warning';
    default:
      return 'default';
  }
}

export const ActiveRunView: React.FC<Props> = ({ run }) => {
  const [tab, setTab] = useState<number>(0);
  const [copied, setCopied] = useState<boolean>(false);
  const [elapsedMs, setElapsedMs] = useState<number>(0);

  const isRunning = run.status === 'RUNNING' || run.status === 'PENDING';

  useEffect(() => {
    if (!isRunning) {
      setElapsedMs(run.durationMs || 0);
      return;
    }

    const start = new Date(run.startedAt).getTime();
    const interval = setInterval(() => {
      setElapsedMs(Date.now() - start);
    }, 200);

    return () => clearInterval(interval);
  }, [isRunning, run.startedAt, run.durationMs]);

  const handleCopyRunId = () => {
    navigator.clipboard.writeText(run.runId);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  const formattedDuration = (elapsedMs / 1000).toFixed(2);

  return (
    <Card variant="outlined" sx={{ mb: 4, borderRadius: 2 }}>
      <CardContent sx={{ pb: 1 }}>
        <Stack
          direction={{ xs: 'column', sm: 'row' }}
          spacing={2}
          sx={{ justifyContent: 'space-between', alignItems: { xs: 'flex-start', sm: 'center' }, mb: 2 }}
        >
          <Box>
            <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
              <Typography variant="h6" sx={{ fontWeight: 700 }}>
                Scenario Run: {run.scenarioId}
              </Typography>
              <Chip
                label={run.status}
                color={getStatusChipColor(run.status)}
                size="small"
                sx={{ fontWeight: 700 }}
              />
            </Stack>

            <Stack direction="row" spacing={1} sx={{ alignItems: 'center', mt: 0.5 }}>
              <Typography variant="caption" color="text.secondary">
                Run ID: <code>{run.runId}</code>
              </Typography>
              <Tooltip title={copied ? 'Copied!' : 'Copy Run ID'}>
                <IconButton size="small" onClick={handleCopyRunId}>
                  {copied ? <CheckIcon fontSize="inherit" color="success" /> : <ContentCopyIcon fontSize="inherit" />}
                </IconButton>
              </Tooltip>
            </Stack>
          </Box>

          <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
            <TimerIcon color="action" fontSize="small" />
            <Typography variant="body2" sx={{ fontWeight: 600, fontFamily: 'monospace' }}>
              {formattedDuration}s
            </Typography>
          </Stack>
        </Stack>

        {run.error && (
          <Alert severity={run.status === 'TIMED_OUT' ? 'warning' : 'error'} sx={{ mb: 2 }}>
            <strong>
              {run.status === 'TIMED_OUT'
                ? 'Execution Timeout:'
                : run.invariantResults?.some((r) => !r.passed)
                ? 'Financial Invariant Violation:'
                : 'Execution Error:'}
            </strong>{' '}
            {run.error}
          </Alert>
        )}

        <Tabs
          value={tab}
          onChange={(_, newTab) => setTab(newTab)}
          sx={{ borderBottom: 1, borderColor: 'divider', mb: 2 }}
        >
          <Tab label={`Live Timeline (${run.timeline.length})`} />
          <Tab label={`Mathematical Invariants (${getApplicableInvariants(run.scenarioId).length})`} />
        </Tabs>

        {tab === 0 && <RunTimeline timeline={run.timeline} isRunning={isRunning} />}
        {tab === 1 && <InvariantReportCards run={run} />}
      </CardContent>
    </Card>
  );
};
