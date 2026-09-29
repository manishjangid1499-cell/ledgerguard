import { UserRole } from '../types/user.types';

export function formatRoleLabel(role: UserRole): string {
  return { CUSTOMER: 'Customer', MERCHANT: 'Merchant', OPS: 'Operations' }[role];
}

const ENUM_LABELS: Record<string, string> = {
  // Triggers
  ON_DEMAND: 'On demand',
  SCHEDULED: 'Scheduled',

  // Run & General Statuses
  RUNNING: 'Running',
  COMPLETED: 'Completed',
  FAILED: 'Failed',

  // Classifications
  DISCREPANCY: 'Discrepancy',
  UNRESOLVED: 'Unresolved',

  // Check Levels
  JOURNAL_BALANCE: 'Journal balance',
  SNAPSHOT_CONSISTENCY: 'Snapshot consistency',
  PROVIDER_SETTLEMENT: 'Provider settlement',

  // Problem Types
  UNBALANCED_JOURNAL: 'Unbalanced journal',
  MALFORMED_JOURNAL: 'Malformed journal',
  SNAPSHOT_MISMATCH: 'Snapshot mismatch',
  SNAPSHOT_MISSING: 'Snapshot missing',
  PROVIDER_STATUS_MISMATCH: 'Provider status mismatch',
  PROVIDER_IDENTITY_MISMATCH: 'Provider identity mismatch',
  PROVIDER_NOT_FOUND: 'Provider not found',
  PROVIDER_UNAVAILABLE: 'Provider unavailable',
  PROVIDER_STILL_PROCESSING: 'Provider still processing',

  // Case Statuses
  OPEN: 'Open',
  IN_REVIEW: 'In review',
  RESOLVED: 'Resolved',

  // Resolution Actions
  SNAPSHOT_REPAIRED: 'Snapshot repaired',
  ALREADY_CONSISTENT: 'Already consistent',
  MANUAL_REVIEW_COMPLETED: 'Manual review completed',

  // Entity Types
  JOURNAL_TRANSACTION: 'Journal transaction',
  LEDGER_ACCOUNT: 'Ledger account',
  FUNDING_OPERATION: 'Funding operation',
  PAYOUT: 'Payout',
};

export function formatEnumLabel(value: string | null | undefined): string {
  if (!value) return '—';
  const label = ENUM_LABELS[value];
  if (label) return label;
  return `Unknown (${value})`;
}

export function formatDateTime(
  value: string | number | Date | null | undefined,
  fallback = '—'
): string {
  if (!value) return fallback;
  const date = typeof value === 'object' && value instanceof Date ? value : new Date(value);
  if (Number.isNaN(date.getTime())) return fallback;

  try {
    const parts = new Intl.DateTimeFormat('en-US', {
      timeZone: 'Asia/Kolkata',
      day: 'numeric',
      month: 'short',
      year: 'numeric',
      hour: 'numeric',
      minute: '2-digit',
      second: '2-digit',
      hour12: true,
    }).formatToParts(date);

    const map = Object.fromEntries(parts.map((p) => [p.type, p.value]));
    const period = (map.dayPeriod || '').toUpperCase();
    return `${map.day} ${map.month} ${map.year}, ${map.hour}:${map.minute}:${map.second} ${period} IST`;
  } catch {
    return date.toLocaleString('en-IN', { timeZone: 'Asia/Kolkata' });
  }
}

export function sanitizeOperatorDescription(text: string | null | undefined): string {
  if (!text) return '—';
  let sanitized = text.split('\n')[0].trim();
  sanitized = sanitized.replace(
    /^(?:[a-zA-Z_$][a-zA-Z0-9_$]*\.)+([a-zA-Z0-9_$]+Exception|[a-zA-Z0-9_$]+Error):\s*/,
    ''
  );
  sanitized = sanitized.replace(/(?:[a-zA-Z_$][a-zA-Z0-9_$]*\.)+([a-zA-Z0-9_$]+)/g, '$1');
  return sanitized.trim() || 'Reconciliation discrepancy detected';
}
