import React from 'react';
import {
  Box,
  Button,
  Chip,
  Paper,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Typography,
} from '@mui/material';
import VisibilityIcon from '@mui/icons-material/Visibility';
import { LabRunView, ScenarioStatus } from '../types/failureLab.types';

interface Props {
  runs: LabRunView[];
  selectedRunId?: string;
  onSelectRun: (runId: string) => void;
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

export const RunHistoryTable: React.FC<Props> = ({ runs, selectedRunId, onSelectRun }) => {
  if (!runs || runs.length === 0) {
    return (
      <Box sx={{ p: 3, textAlign: 'center' }}>
        <Typography variant="body2" color="text.secondary">
          No recent runs recorded. Choose a scenario above to start your first chaos run.
        </Typography>
      </Box>
    );
  }

  return (
    <Box>
      <Typography variant="h6" sx={{ fontWeight: 700, mb: 2 }}>
        Recent Execution History
      </Typography>

      <TableContainer component={Paper} variant="outlined" sx={{ borderRadius: 2 }}>
        <Table size="small">
          <TableHead sx={{ bgcolor: 'action.hover' }}>
            <TableRow>
              <TableCell sx={{ fontWeight: 700 }}>Run ID</TableCell>
              <TableCell sx={{ fontWeight: 700 }}>Scenario</TableCell>
              <TableCell sx={{ fontWeight: 700 }}>Status</TableCell>
              <TableCell sx={{ fontWeight: 700 }}>Started At</TableCell>
              <TableCell sx={{ fontWeight: 700 }}>Duration</TableCell>
              <TableCell sx={{ fontWeight: 700 }}>Invariants</TableCell>
              <TableCell align="right" sx={{ fontWeight: 700 }}>Action</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {runs.map((r) => {
              const isSelected = r.runId === selectedRunId;
              const durationSec = r.durationMs ? `${(r.durationMs / 1000).toFixed(2)}s` : '�';
              const passedInvariants = r.invariantResults ? r.invariantResults.filter((inv) => inv.passed).length : 0;
              const totalInvariants = r.invariantResults ? r.invariantResults.length : 0;

              return (
                <TableRow
                  key={r.runId}
                  hover
                  selected={isSelected}
                  sx={{ cursor: 'pointer' }}
                  onClick={() => onSelectRun(r.runId)}
                >
                  <TableCell sx={{ fontFamily: 'monospace', fontSize: '0.8rem' }}>
                    {r.runId.substring(0, 8)}...
                  </TableCell>
                  <TableCell sx={{ fontWeight: 600 }}>{r.scenarioId}</TableCell>
                  <TableCell>
                    <Chip
                      label={r.status}
                      size="small"
                      color={getStatusChipColor(r.status)}
                      sx={{ fontWeight: 700, fontSize: '0.68rem', height: 22 }}
                    />
                  </TableCell>
                  <TableCell sx={{ fontSize: '0.8rem', color: 'text.secondary' }}>
                    {new Date(r.startedAt).toLocaleTimeString()}
                  </TableCell>
                  <TableCell sx={{ fontSize: '0.8rem', fontFamily: 'monospace' }}>
                    {durationSec}
                  </TableCell>
                  <TableCell sx={{ fontSize: '0.8rem' }}>
                    {totalInvariants > 0 ? `${passedInvariants}/${totalInvariants} Passed` : '�'}
                  </TableCell>
                  <TableCell align="right">
                    <Button
                      size="small"
                      variant={isSelected ? 'contained' : 'outlined'}
                      startIcon={<VisibilityIcon fontSize="small" />}
                      onClick={(e) => {
                        e.stopPropagation();
                        onSelectRun(r.runId);
                      }}
                      sx={{ fontSize: '0.72rem', py: 0.2 }}
                    >
                      {isSelected ? 'Viewing' : 'Inspect'}
                    </Button>
                  </TableCell>
                </TableRow>
              );
            })}
          </TableBody>
        </Table>
      </TableContainer>
    </Box>
  );
};
