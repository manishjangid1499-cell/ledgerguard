import { Chip } from '@mui/material';

const statusColors: Record<string, 'default' | 'success' | 'error' | 'warning' | 'info'> = {
  ACTIVE: 'success', POSTED: 'success', COMPLETED: 'success', PASSED: 'success',
  FROZEN: 'warning', TIMED_OUT: 'warning', FAILED: 'error',
  PENDING: 'info', RUNNING: 'info',
};

export const StatusBadge = ({ status }: { status: string }) => (
  <Chip label={status.charAt(0) + status.slice(1).toLowerCase().replaceAll('_', ' ')}
    color={statusColors[status] || 'default'} variant="outlined" size="small"
    sx={{ fontWeight: 600, fontSize: '0.75rem', maxWidth: '100%' }} />
);
