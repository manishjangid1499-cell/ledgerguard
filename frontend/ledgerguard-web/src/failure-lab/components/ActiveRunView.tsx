import React, { useEffect, useState } from 'react';
import {
  Alert,
  Box,
  Card,
  CardContent,
  Stack,
  Tab,
  Tabs,
  Typography,
} from '@mui/material';
import TimerIcon from '@mui/icons-material/Timer';
import { LabRunView, getApplicableInvariants } from '../types/failureLab.types';
import { CopyButton } from '../../shared/components/CopyButton';
import { StatusBadge } from '../../shared/components/StatusBadge';
import { RunTimeline } from './RunTimeline';
import { InvariantReportCards } from './InvariantReportCards';

interface Props {
  run: LabRunView;
}

export const ActiveRunView: React.FC<Props> = ({ run }) => {
  const [tab, setTab] = useState<number>(0);
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

  const formattedDuration = (elapsedMs / 1000).toFixed(2);

  return (
    <Card variant="outlined" sx={{ mb: 4, borderRadius: 1 }}>
      <CardContent sx={{ pb: 1 }}>
        <Stack
          direction={{ xs: 'column', sm: 'row' }}
          spacing={2}
          sx={{ justifyContent: 'space-between', alignItems: { xs: 'flex-start', sm: 'center' }, mb: 2 }}
        >
          <Box>
            <Stack direction="row" spacing={1} useFlexGap sx={{ alignItems: 'center', flexWrap: 'wrap' }}>
              <Typography variant="h6" component="h2" sx={{ fontWeight: 700, overflowWrap: 'anywhere' }}>
                {run.scenarioId.replaceAll('_', ' ')}
              </Typography>
              <StatusBadge status={run.status} />
            </Stack>

            <Stack direction="row" spacing={1} sx={{ alignItems: 'center', mt: 0.5 }}>
              <Typography variant="caption" color="text.secondary" sx={{ overflowWrap: 'anywhere', minWidth: 0 }}>
                Run ID: <code>{run.runId}</code>
              </Typography>
              <CopyButton value={run.runId} label="run ID" />
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
            The run did not complete successfully. Review the timeline and invariant results for the recorded outcome.
          </Alert>
        )}

        <Tabs
          aria-label="Run details"
          variant="scrollable"
          scrollButtons="auto"
          allowScrollButtonsMobile
          value={tab}
          onChange={(_, newTab) => setTab(newTab)}
          sx={{ borderBottom: 1, borderColor: 'divider', mb: 2 }}
        >
          <Tab id="timeline-tab" aria-controls="timeline-panel" label={`Timeline (${run.timeline.length})`} />
          <Tab id="invariants-tab" aria-controls="invariants-panel" label={`Invariants (${getApplicableInvariants(run.scenarioId).length})`} />
        </Tabs>

        <Box role="tabpanel" id="timeline-panel" aria-labelledby="timeline-tab" hidden={tab !== 0} tabIndex={0}>
          {tab === 0 && <RunTimeline timeline={run.timeline} isRunning={isRunning} />}
        </Box>
        <Box role="tabpanel" id="invariants-panel" aria-labelledby="invariants-tab" hidden={tab !== 1} tabIndex={0}>
          {tab === 1 && <InvariantReportCards run={run} />}
        </Box>
      </CardContent>
    </Card>
  );
};
