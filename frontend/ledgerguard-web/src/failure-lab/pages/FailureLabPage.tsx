import React, { useState } from 'react';
import { Alert, Container, Snackbar } from '@mui/material';
import { FailureLabHeader } from '../components/FailureLabHeader';
import { EnvironmentStatusBanner } from '../components/EnvironmentStatusBanner';
import { ScenarioCardGrid } from '../components/ScenarioCardGrid';
import { ActiveRunView } from '../components/ActiveRunView';
import { RunHistoryTable } from '../components/RunHistoryTable';
import {
  useLabActiveRun,
  useLabHistory,
  useLabRun,
  useLabScenarios,
  useStartScenario,
} from '../hooks/useFailureLab';
import { ScenarioId } from '../types/failureLab.types';

export const FailureLabPage: React.FC = () => {
  const { data: scenarios = [] } = useLabScenarios();
  const { data: activeRun } = useLabActiveRun();
  const { data: history = [] } = useLabHistory(20);
  const startScenario = useStartScenario();

  const [selectedRunId, setSelectedRunId] = useState<string | null>(null);
  const [activeScenarioTriggered, setActiveScenarioTriggered] = useState<ScenarioId | undefined>(undefined);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  // If there is an active run, prefer viewing that run
  const viewingRunId = activeRun?.runId || selectedRunId || (history.length > 0 ? history[0].runId : null);
  const { data: detailedRun } = useLabRun(viewingRunId);

  const isRunActive = Boolean(activeRun && (activeRun.status === 'RUNNING' || activeRun.status === 'PENDING'));

  const handleStartScenario = (scenarioId: ScenarioId) => {
    setActiveScenarioTriggered(scenarioId);
    setErrorMessage(null);

    startScenario.mutate(scenarioId, {
      onSuccess: (newRun) => {
        setSelectedRunId(newRun.runId);
        setActiveScenarioTriggered(undefined);
      },
      onError: (err: unknown) => {
        setActiveScenarioTriggered(undefined);
        const errObj = err as { status?: number; message?: string };
        if (errObj?.status === 409) {
          setErrorMessage('Another scenario is currently executing. Max 1 active run permitted.');
        } else {
          setErrorMessage(errObj?.message || 'Failed to start scenario run.');
        }
      },
    });
  };

  return (
    <Container maxWidth="lg">
      <FailureLabHeader />
      <EnvironmentStatusBanner />

      {errorMessage && (
        <Alert severity="warning" onClose={() => setErrorMessage(null)} sx={{ mb: 3 }}>
          {errorMessage}
        </Alert>
      )}

      {scenarios.length > 0 && (
        <ScenarioCardGrid
          scenarios={scenarios}
          isRunActive={isRunActive}
          onSelectScenario={handleStartScenario}
          isStarting={startScenario.isPending}
          activeScenarioId={activeScenarioTriggered}
        />
      )}

      {detailedRun && <ActiveRunView run={detailedRun} />}

      <RunHistoryTable
        runs={history}
        selectedRunId={viewingRunId || undefined}
        onSelectRun={(runId) => setSelectedRunId(runId)}
      />

      <Snackbar
        open={Boolean(errorMessage)}
        autoHideDuration={6000}
        onClose={() => setErrorMessage(null)}
        message={errorMessage}
      />
    </Container>
  );
};
