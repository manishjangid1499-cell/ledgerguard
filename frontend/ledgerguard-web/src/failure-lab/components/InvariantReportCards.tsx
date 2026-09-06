import React from 'react';
import {
  Box,
  Card,
  CardContent,
  Chip,
  Grid,
  Stack,
  Typography,
} from '@mui/material';
import CheckCircleIcon from '@mui/icons-material/CheckCircle';
import HighlightOffIcon from '@mui/icons-material/HighlightOff';
import HourglassTopIcon from '@mui/icons-material/HourglassTop';
import {
  InvariantCheckResult,
  LabRunView,
  getApplicableInvariants,
} from '../types/failureLab.types';

interface Props {
  run: LabRunView;
}

export const InvariantReportCards: React.FC<Props> = ({ run }) => {
  const isPendingOrRunning = run.status === 'PENDING' || run.status === 'RUNNING';
  const invariantResults = run.invariantResults || [];
  const applicableDefs = getApplicableInvariants(run.scenarioId);

  return (
    <Box sx={{ py: 1 }}>
      <Typography variant="subtitle2" sx={{ fontWeight: 700, mb: 2 }}>
        Scenario Invariant Evaluation ({applicableDefs.length} applicable)
      </Typography>

      <Grid container spacing={2}>
        {applicableDefs.map((def) => {
          const matching = invariantResults.filter((res) =>
            def.matchPatterns.some((pat) => res.invariantName.toLowerCase().includes(pat.toLowerCase()))
          );

          let status: 'PASSED' | 'FAILED' | 'CHECKING' | 'NOT_EVALUATED' = 'NOT_EVALUATED';
          let evidence: InvariantCheckResult | undefined = undefined;

          if (isPendingOrRunning) {
            status = 'CHECKING';
          } else if (matching.length > 0) {
            const allPassed = matching.every((m) => m.passed);
            status = allPassed ? 'PASSED' : 'FAILED';
            evidence = matching[0];
          }

          return (
            <Grid key={def.key} size={{ xs: 12, md: 6 }}>
              <Card
                variant="outlined"
                sx={{
                  height: '100%',
                  borderRadius: 1.5,
                  bgcolor: status === 'PASSED'
                    ? 'rgba(46, 125, 50, 0.04)'
                    : status === 'FAILED'
                    ? 'rgba(211, 47, 47, 0.04)'
                    : 'background.paper',
                  borderColor: status === 'PASSED'
                    ? 'success.light'
                    : status === 'FAILED'
                    ? 'error.light'
                    : 'divider',
                }}
              >
                <CardContent sx={{ p: 2, '&:last-child': { pb: 2 } }}>
                  <Stack
                    direction="row"
                    spacing={1}
                    sx={{ justifyContent: 'space-between', alignItems: 'flex-start', mb: 1 }}
                  >
                    <Box>
                      <Typography variant="subtitle2" sx={{ fontWeight: 700 }}>
                        {def.name}
                      </Typography>
                      <Typography
                        variant="caption"
                        sx={{
                          fontFamily: 'monospace',
                          color: 'primary.main',
                          fontWeight: 600,
                        }}
                      >
                        {def.formula}
                      </Typography>
                    </Box>

                    {status === 'PASSED' && (
                      <Chip
                        icon={<CheckCircleIcon />}
                        label="VERIFIED"
                        color="success"
                        size="small"
                        sx={{ fontWeight: 700, fontSize: '0.68rem' }}
                      />
                    )}
                    {status === 'FAILED' && (
                      <Chip
                        icon={<HighlightOffIcon />}
                        label="VIOLATION"
                        color="error"
                        size="small"
                        sx={{ fontWeight: 700, fontSize: '0.68rem' }}
                      />
                    )}
                    {status === 'CHECKING' && (
                      <Chip
                        icon={<HourglassTopIcon />}
                        label="VERIFYING"
                        color="warning"
                        size="small"
                        sx={{ fontWeight: 700, fontSize: '0.68rem' }}
                      />
                    )}
                    {status === 'NOT_EVALUATED' && (
                      <Chip
                        label="NOT EVALUATED"
                        size="small"
                        variant="outlined"
                        sx={{ fontSize: '0.65rem' }}
                      />
                    )}
                  </Stack>

                  <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mb: 1.5 }}>
                    {def.description}
                  </Typography>

                  {evidence && (
                    <Box sx={{ p: 1, bgcolor: 'background.default', borderRadius: 1 }}>
                      <Stack direction="row" sx={{ justifyContent: 'space-between' }}>
                        <Typography variant="caption" color="text.secondary">
                          Expected: <strong>{evidence.expected}</strong>
                        </Typography>
                        <Typography variant="caption" color="text.secondary">
                          Observed: <strong>{evidence.actual}</strong>
                        </Typography>
                      </Stack>
                      {evidence.details && (
                        <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 0.5 }}>
                          Details: {evidence.details}
                        </Typography>
                      )}
                    </Box>
                  )}
                </CardContent>
              </Card>
            </Grid>
          );
        })}
      </Grid>
    </Box>
  );
};
