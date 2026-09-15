import { Alert, AlertTitle, Chip, Paper, Stack, Typography } from '@mui/material';
import { useLabEnvironment } from '../hooks/useFailureLab';
import { DataLoading } from '../../shared/components/DataLoading';

export const EnvironmentStatusBanner = () => {
  const { data: env, isLoading, isError } = useLabEnvironment();
  if (isLoading) return <Paper variant="outlined" sx={{ p: 2, mb: 3 }}><DataLoading label="Checking lab availability" minHeight={100} /></Paper>;
  if (isError || !env || env.status === 'DISABLED') return (
    <Alert severity="warning" variant="outlined" sx={{ mb: 3, bgcolor: 'background.paper' }}>
      <AlertTitle>Lab unavailable</AlertTitle>
      The isolated lab service is offline or disabled. Scenarios will be available when the service is running.
    </Alert>
  );
  return (
    <Paper variant="outlined" sx={{ p: 2, mb: 3 }}>
      <Stack direction={{ xs: 'column', md: 'row' }} spacing={2} sx={{ justifyContent: 'space-between', alignItems: { md: 'center' } }}>
        <Typography variant="subtitle2">Lab environment</Typography>
        <Stack direction="row" spacing={1} useFlexGap sx={{ flexWrap: 'wrap' }}>
          {[['PostgreSQL', env.postgresStatus], ['Kafka', env.kafkaStatus], ['PSP adapter', env.pspAdapterStatus]].map(([name, status]) => (
            <Chip key={name} label={name + ': ' + status.toLowerCase().replaceAll('_', ' ')} size="small"
              color={status === 'UP' ? 'success' : 'default'} variant="outlined" />
          ))}
        </Stack>
      </Stack>
    </Paper>
  );
};
