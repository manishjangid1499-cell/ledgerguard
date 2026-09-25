import { Chip } from '@mui/material';

const statusColors: Record<string, 'default' | 'success' | 'error' | 'warning' | 'info'> = {
  ACTIVE: 'success', POSTED: 'success', COMPLETED: 'success', PASSED: 'success',
  FROZEN: 'warning', TIMED_OUT: 'warning', FAILED: 'error',
  PENDING: 'info', RUNNING: 'info',
  SUCCEEDED: 'success', PROCESSING: 'info', CREATED: 'info', UNKNOWN: 'warning', RECONCILIATION_REQUIRED: 'warning',
};

const statusLabels: Record<string, string> = {
  SUCCEEDED: 'Completed', UNKNOWN: 'Confirmation pending', RECONCILIATION_REQUIRED: 'Pending review',
};

export const StatusBadge = ({ status }: { status?: string }) => {
  if (!status) return null;
  return (
    <Chip
      label={statusLabels[status] || status.charAt(0) + status.slice(1).toLowerCase().replaceAll('_', ' ')}
      color={statusColors[status] || 'default'}
      variant="outlined"
      size="small"
      sx={{ fontWeight: 600, fontSize: '0.75rem', maxWidth: '100%' }}
    />
  );
};
