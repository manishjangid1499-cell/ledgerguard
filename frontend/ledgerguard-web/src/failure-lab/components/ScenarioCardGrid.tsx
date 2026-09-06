import React from 'react';
import {
  Box,
  Button,
  Card,
  CardActions,
  CardContent,
  Chip,
  Grid,
  Stack,
  Typography,
  CircularProgress,
} from '@mui/material';
import PlayArrowIcon from '@mui/icons-material/PlayArrow';
import SyncAltIcon from '@mui/icons-material/SyncAlt';
import CloudOffIcon from '@mui/icons-material/CloudOff';
import BuildCircleIcon from '@mui/icons-material/BuildCircle';
import DynamicFeedIcon from '@mui/icons-material/DynamicFeed';
import { ScenarioId, ScenarioMetadataView } from '../types/failureLab.types';

interface Props {
  scenarios: ScenarioMetadataView[];
  isRunActive: boolean;
  onSelectScenario: (scenarioId: ScenarioId) => void;
  isStarting: boolean;
  activeScenarioId?: ScenarioId;
}

function getScenarioIcon(id: ScenarioId) {
  switch (id) {
    case 'OPPOSING_TRANSFERS':
      return <SyncAltIcon color="primary" />;
    case 'TIMEOUT_AFTER_COMMIT':
      return <CloudOffIcon color="warning" />;
    case 'CORRUPTED_SNAPSHOT':
      return <BuildCircleIcon color="error" />;
    case 'WEBHOOK_RACE':
      return <DynamicFeedIcon color="info" />;
    default:
      return <PlayArrowIcon />;
  }
}

function getCategoryColor(category: string): 'primary' | 'secondary' | 'warning' | 'error' | 'info' | 'default' {
  switch (category) {
    case 'CONCURRENCY':
      return 'primary';
    case 'DISTRIBUTED_FAULT':
      return 'warning';
    case 'DATA_INTEGRITY':
      return 'error';
    case 'IDEMPOTENCY':
      return 'info';
    default:
      return 'default';
  }
}

export const ScenarioCardGrid: React.FC<Props> = ({
  scenarios,
  isRunActive,
  onSelectScenario,
  isStarting,
  activeScenarioId,
}) => {
  return (
    <Box sx={{ mb: 4 }}>
      <Typography variant="h6" sx={{ fontWeight: 700, mb: 2 }}>
        Available Chaos Scenarios
      </Typography>

      <Grid container spacing={2.5}>
        {scenarios.map((scenario) => {
          const isThisStarting = isStarting && activeScenarioId === scenario.id;

          return (
            <Grid key={scenario.id} size={{ xs: 12, md: 6 }}>
              <Card
                variant="outlined"
                sx={{
                  height: '100%',
                  display: 'flex',
                  flexDirection: 'column',
                  borderRadius: 2,
                  transition: 'all 0.2s ease-in-out',
                  '&:hover': {
                    boxShadow: 2,
                    borderColor: 'primary.light',
                  },
                }}
              >
                <CardContent sx={{ flexGrow: 1, pb: 1 }}>
                  <Stack
                    direction="row"
                    spacing={1}
                    sx={{ justifyContent: 'space-between', alignItems: 'flex-start', mb: 1.5 }}
                  >
                    <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center' }}>
                      {getScenarioIcon(scenario.id)}
                      <Typography variant="subtitle1" sx={{ fontWeight: 700 }}>
                        {scenario.name}
                      </Typography>
                    </Stack>
                    <Chip
                      label={scenario.category}
                      size="small"
                      color={getCategoryColor(scenario.category)}
                      variant="outlined"
                      sx={{ fontWeight: 600, fontSize: '0.7rem' }}
                    />
                  </Stack>

                  <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
                    {scenario.description}
                  </Typography>

                  <Box sx={{ mb: 1.5 }}>
                    <Typography variant="caption" color="text.secondary" sx={{ fontWeight: 700, display: 'block', mb: 0.5 }}>
                      Fault Mechanisms:
                    </Typography>
                    <Stack direction="row" spacing={0.5} sx={{ flexWrap: 'wrap' }}>
                      {scenario.faultMechanisms.map((mech, idx) => (
                        <Chip
                          key={idx}
                          label={mech}
                          size="small"
                          sx={{ fontSize: '0.72rem', bgcolor: 'action.hover', mb: 0.5 }}
                        />
                      ))}
                    </Stack>
                  </Box>

                  <Box>
                    <Typography variant="caption" color="text.secondary" sx={{ fontWeight: 700, display: 'block', mb: 0.5 }}>
                      Key Invariants Verified:
                    </Typography>
                    <Stack direction="column" spacing={0.3}>
                      {scenario.keyInvariants.map((inv, idx) => (
                        <Typography key={idx} variant="caption" color="text.secondary">
                          � {inv}
                        </Typography>
                      ))}
                    </Stack>
                  </Box>
                </CardContent>

                <CardActions sx={{ p: 2, pt: 0 }}>
                  <Button
                    variant="contained"
                    color="primary"
                    fullWidth
                    disabled={isRunActive || isStarting}
                    onClick={() => onSelectScenario(scenario.id)}
                    startIcon={isThisStarting ? <CircularProgress size={16} color="inherit" /> : <PlayArrowIcon />}
                    sx={{ fontWeight: 600 }}
                  >
                    {isThisStarting ? 'Dispatching Run...' : 'Execute Chaos Run'}
                  </Button>
                </CardActions>
              </Card>
            </Grid>
          );
        })}
      </Grid>
    </Box>
  );
};
