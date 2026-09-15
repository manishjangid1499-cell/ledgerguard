import React from 'react';
import {
  Box,
  Button,
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
import { LabRunView } from '../types/failureLab.types';
import { StatusBadge } from '../../shared/components/StatusBadge';
import { formatDateTime } from '../../shared/utils/display';

interface Props {
  runs: LabRunView[];
  selectedRunId?: string;
  onSelectRun: (runId: string) => void;
}

export const RunHistoryTable: React.FC<Props> = ({ runs, selectedRunId, onSelectRun }) => {
  if (!runs || runs.length === 0) {
    return (
      <Box sx={{ p: 3, textAlign: 'center' }}>
        <Typography variant="body2" color="text.secondary">
          No runs recorded yet. Choose an available scenario to begin.
        </Typography>
      </Box>
    );
  }

  return (
    <Box>
      <Typography variant="h6" component="h2" sx={{ fontWeight: 700, mb: 2 }}>
        Recent runs
      </Typography>

      <TableContainer component={Paper} variant="outlined" tabIndex={0} role="region" aria-label="Run history, scroll horizontally for more columns" sx={{ borderRadius: 2 }}>
        <Table size="small" aria-label="Failure Lab run history" sx={{ minWidth: 780 }}>
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
              const durationSec = r.durationMs ? `${(r.durationMs / 1000).toFixed(2)}s` : '—';
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
                    <StatusBadge status={r.status} />
                  </TableCell>
                  <TableCell sx={{ fontSize: '0.8rem', color: 'text.secondary' }}>
                    {formatDateTime(r.startedAt)}
                  </TableCell>
                  <TableCell sx={{ fontSize: '0.8rem', fontFamily: 'monospace' }}>
                    {durationSec}
                  </TableCell>
                  <TableCell sx={{ fontSize: '0.8rem' }}>
                    {totalInvariants > 0 ? `${passedInvariants}/${totalInvariants} Passed` : '—'}
                  </TableCell>
                  <TableCell align="right">
                    <Button
                      size="small"
                      aria-label={`Inspect run ${r.runId}`}
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
