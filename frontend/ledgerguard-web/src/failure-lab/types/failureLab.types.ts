export type ScenarioId =
  | 'OPPOSING_TRANSFERS'
  | 'TIMEOUT_AFTER_COMMIT'
  | 'CORRUPTED_SNAPSHOT'
  | 'WEBHOOK_RACE';

export type ScenarioStatus = 'PENDING' | 'RUNNING' | 'PASSED' | 'FAILED' | 'TIMED_OUT';

export interface ScenarioStepEvent {
  timestamp: string;
  stepName: string;
  description: string;
  details?: string | null;
}

export interface InvariantCheckResult {
  invariantName: string;
  passed: boolean;
  expected: string;
  actual: string;
  details?: string | null;
}

export interface LabRunView {
  runId: string;
  scenarioId: ScenarioId;
  status: ScenarioStatus;
  startedAt: string;
  completedAt?: string | null;
  durationMs?: number | null;
  timeline: ScenarioStepEvent[];
  invariantResults: InvariantCheckResult[];
  error?: string | null;
}

export interface ScenarioMetadataView {
  id: ScenarioId;
  name: string;
  description: string;
  category: string;
  keyInvariants: string[];
  faultMechanisms: string[];
}

export interface EnvironmentStatusView {
  status: string;
  postgresStatus: string;
  kafkaStatus: string;
  pspAdapterStatus: string;
  mode: string;
  safeNotice: string;
}

export interface StartScenarioRequest {
  scenarioId: ScenarioId;
}

export interface InvariantDefinition {
  key: string;
  name: string;
  formula: string;
  description: string;
  matchPatterns: string[];
  applicableScenarios: ScenarioId[];
}

export const INVARIANT_DEFINITIONS: InvariantDefinition[] = [
  {
    key: 'JOURNAL_INTEGRITY',
    name: 'Journal Integrity',
    formula: 'Sum(Debits) == Sum(Credits) [Diff = 0]',
    description: 'Every posted journal entry is cryptographically and structurally balanced with zero discrepancy.',
    matchPatterns: ['Debit == Credit', 'Structural Balance', 'DEBIT_CREDIT_EQUALITY', 'Journal Structure'],
    applicableScenarios: ['OPPOSING_TRANSFERS', 'TIMEOUT_AFTER_COMMIT', 'CORRUPTED_SNAPSHOT', 'WEBHOOK_RACE'],
  },
  {
    key: 'SNAPSHOT_PARITY',
    name: 'Snapshot Parity',
    formula: 'Snapshot == Sum(Posted Journals)',
    description: 'Materialized balance snapshot matches the ground truth reconstructed from immutable journal lines.',
    matchPatterns: ['Snapshot Integrity', 'Reconstructed Balance', 'SNAPSHOT_INTEGRITY'],
    applicableScenarios: ['OPPOSING_TRANSFERS', 'TIMEOUT_AFTER_COMMIT', 'CORRUPTED_SNAPSHOT', 'WEBHOOK_RACE'],
  },
  {
    key: 'AVAILABLE_BALANCE',
    name: 'Available Balance Bound',
    formula: 'Available == Balance - Holds >= 0',
    description: 'Customer available balance never falls below zero, and active holds correctly reserve funds.',
    matchPatterns: ['Available Balance', 'AVAILABLE_BALANCE'],
    applicableScenarios: ['OPPOSING_TRANSFERS', 'TIMEOUT_AFTER_COMMIT', 'CORRUPTED_SNAPSHOT', 'WEBHOOK_RACE'],
  },
  {
    key: 'INTERNAL_TRANSFER_CONSERVATION',
    name: 'Internal Transfer Conservation',
    formula: 'Delta Balance(A) + Delta Balance(B) == 0',
    description: 'Internal transfer pair conserves total money with net change across participating accounts equaling zero.',
    matchPatterns: ['Internal Transfer Conservation', 'Conservation of Money', 'CONSERVATION'],
    applicableScenarios: ['OPPOSING_TRANSFERS'],
  },
  {
    key: 'SINGLE_ECONOMIC_EFFECT',
    name: 'Single Economic Effect',
    formula: 'Settlement Journal Count == 1',
    description: 'Exactly one authoritative journal entry is posted despite external timeouts, retries, or webhook replays.',
    matchPatterns: ['Settlement Journal Count', 'Single Settlement', 'SINGLE_ECONOMIC_EFFECT'],
    applicableScenarios: ['TIMEOUT_AFTER_COMMIT', 'WEBHOOK_RACE'],
  },
];

export function getApplicableInvariants(scenarioId: ScenarioId): InvariantDefinition[] {
  return INVARIANT_DEFINITIONS.filter((def) => def.applicableScenarios.includes(scenarioId));
}

