import React, { useState } from 'react';
import { Alert, Container } from '@mui/material';
import { DataLoading } from '../../shared/components/DataLoading';
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
  const { data: scenarios = [], isLoading: loadingScenarios, isError: scenariosError } = useLabScenarios();
  const { data: activeRun, isError: activeRunError } = useLabActiveRun();
  const { data: history = [], isLoading: loadingHistory, isError: historyError } = useLabHistory(20);
  const startScenario = useStartScenario();

  const [selectedRunId, setSelectedRunId] = useState<string | null>(null);
  const [activeScenarioTriggered, setActiveScenarioTriggered] = useState<ScenarioId | undefined>(undefined);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  // If there is an active run, prefer viewing that run
  const viewingRunId = activeRun?.runId || selectedRunId || (history.length > 0 ? history[0].runId : null);
  const { data: detailedRun, isLoading: loadingRun, isError: runError } = useLabRun(viewingRunId);

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
        const errObj = err as { status?: number };
        if (errObj?.status === 409) {
          setErrorMessage('Another scenario is running. Wait for it to finish before starting a new run.');
        } else {
          setErrorMessage('The scenario start could not be confirmed. Check the active run before trying again.');
        }
      },
    });
  };

  return (
    <Container maxWidth="lg">
      <FailureLabHeader />
      <EnvironmentStatusBanner />
      {loadingScenarios && <DataLoading label="Loading available scenarios" />}
      {!loadingScenarios && !scenariosError && scenarios.length === 0 && <Alert severity="info" sx={{ mb: 3 }}>No scenarios are available.</Alert>}
      {scenariosError && <Alert severity="error" sx={{ mb: 3 }}>Unable to load available scenarios.</Alert>}
      {!scenariosError && activeRunError && <Alert severity="warning" sx={{ mb: 3 }}>The active run could not be checked. Scenario status may be out of date.</Alert>}

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

      {loadingRun && <DataLoading label="Loading run details" />}
      {runError && <Alert severity="error" sx={{ mb: 3 }}>Unable to load the selected run.</Alert>}
      {detailedRun && <ActiveRunView key={detailedRun.runId} run={detailedRun} />}

      {loadingHistory ? <DataLoading label="Loading run history" /> : historyError ? (
        !scenariosError && <Alert severity="error">Unable to load run history.</Alert>
      ) : <RunHistoryTable
        runs={history}
        selectedRunId={viewingRunId || undefined}
        onSelectRun={(runId) => setSelectedRunId(runId)}
      />}
    </Container>
  );
};
