import React from 'react';
import { Box, Typography, Stack, Paper, Chip } from '@mui/material';
import HourglassEmptyIcon from '@mui/icons-material/HourglassEmpty';
import { ScenarioStepEvent } from '../types/failureLab.types';

interface Props {
  timeline: ScenarioStepEvent[];
  isRunning: boolean;
}

function getStepColor(stepName: string): 'default' | 'primary' | 'warning' | 'error' | 'success' | 'info' {
  switch (stepName) {
    case 'INIT':
    case 'ENVIRONMENT_VERIFIED':
    case 'LOCK_ACQUIRED':
      return 'default';
    case 'SETUP':
    case 'DISPATCH':
    case 'DISPATCH_PAYOUT':
    case 'DISPATCH_RACE':
      return 'primary';
    case 'INJECT_FAULT':
    case 'INJECT_DRIFT':
      return 'warning';
    case 'AMBIGUITY_CONFIRMED':
    case 'DISCREPANCY_DETECTED':
      return 'info';
    case 'TRIGGER_RECOVERY':
    case 'RUN_DETECTION':
    case 'RUN_REPAIR':
      return 'primary';
    case 'SETTLED':
    case 'REPAIRED':
    case 'RACE_SETTLED':
    case 'COMPLETED':
    case 'IDEMPOTENCY_VERIFIED':
      return 'success';
    case 'TIMEOUT':
    case 'ERROR':
      return 'error';
    default:
      return 'default';
  }
}

export const RunTimeline: React.FC<Props> = ({ timeline, isRunning }) => {
  if (!timeline || timeline.length === 0) {
    return (
      <Box sx={{ p: 3, textAlign: 'center' }}>
        <Typography variant="body2" color="text.secondary">
          No execution events recorded yet.
        </Typography>
      </Box>
    );
  }

  return (
    <Box sx={{ py: 1 }}>
      <Stack spacing={1.5}>
        {timeline.map((event, index) => {
          const isLast = index === timeline.length - 1;
          const color = getStepColor(event.stepName);

          return (
            <Paper
              key={index}
              variant="outlined"
              sx={{
                p: 1.5,
                borderRadius: 1.5,
                bgcolor: isLast && isRunning ? 'action.hover' : 'background.paper',
                borderColor: isLast && isRunning ? 'primary.main' : 'divider',
              }}
            >
              <Stack
                direction="row"
                spacing={1}
                sx={{ justifyContent: 'space-between', alignItems: 'flex-start', mb: 0.5 }}
              >
                <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
                  <Chip
                    label={event.stepName}
                    size="small"
                    color={color}
                    sx={{ fontWeight: 700, fontSize: '0.68rem', height: 20 }}
                  />
                  <Typography variant="body2" sx={{ fontWeight: 600 }}>
                    {event.description}
                  </Typography>
                </Stack>
                <Typography variant="caption" color="text.secondary" sx={{ whiteSpace: 'nowrap' }}>
                  {new Date(event.timestamp).toLocaleTimeString([], { hour12: false, hour: '2-digit', minute: '2-digit', second: '2-digit', fractionalSecondDigits: 3 })}
                </Typography>
              </Stack>

              {event.details && (
                <Box
                  component="pre"
                  sx={{
                    m: 0,
                    mt: 1,
                    p: 1,
                    borderRadius: 1,
                    bgcolor: 'action.hover',
                    fontSize: '0.75rem',
                    overflowX: 'auto',
                  }}
                >
                  {event.details}
                </Box>
              )}
            </Paper>
          );
        })}

        {isRunning && (
          <Paper
            variant="outlined"
            sx={{
              p: 1.5,
              borderRadius: 1.5,
              borderStyle: 'dashed',
              bgcolor: 'action.hover',
              display: 'flex',
              alignItems: 'center',
              gap: 1.5,
            }}
          >
            <HourglassEmptyIcon color="action" fontSize="small" sx={{ animation: 'spin 2s linear infinite' }} />
            <Typography variant="caption" color="text.secondary" sx={{ fontWeight: 500 }}>
              Executing next step...
            </Typography>
          </Paper>
        )}
      </Stack>
    </Box>
  );
};
