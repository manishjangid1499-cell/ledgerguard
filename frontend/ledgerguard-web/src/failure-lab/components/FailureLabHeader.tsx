import { Box, Chip, Stack, Typography } from '@mui/material';

export const FailureLabHeader = () => (
  <Box sx={{ mb: 3 }}>
    <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2} sx={{ justifyContent: 'space-between', alignItems: { sm: 'center' }, mb: 1 }}>
      <Typography variant="h4" component="h1" color="primary.main" sx={{ fontSize: { xs: '1.75rem', md: '2rem' } }}>Financial integrity lab</Typography>
      <Chip label="Isolated environment" variant="outlined" color="info" size="small" />
    </Stack>
    <Typography variant="body2" color="text.secondary" sx={{ maxWidth: 800, lineHeight: 1.7 }}>
      Observe how financial workflows respond to concurrent requests, provider timeouts and delivery faults.
      Scenarios use disposable infrastructure and verify ledger invariants independently.
    </Typography>
  </Box>
);
