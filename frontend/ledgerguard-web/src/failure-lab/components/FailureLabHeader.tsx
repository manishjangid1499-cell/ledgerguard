import React from 'react';
import { Box, Typography, Stack, Chip, Paper } from '@mui/material';
import ScienceIcon from '@mui/icons-material/Science';
import SecurityIcon from '@mui/icons-material/Security';

export const FailureLabHeader: React.FC = () => {
  return (
    <Box sx={{ mb: 4 }}>
      <Stack
        direction={{ xs: 'column', sm: 'row' }}
        spacing={2}
        sx={{ justifyContent: 'space-between', alignItems: { xs: 'flex-start', sm: 'center' }, mb: 1.5 }}
      >
        <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center' }}>
          <ScienceIcon color="primary" sx={{ fontSize: 32 }} />
          <Typography variant="h4" component="h1" sx={{ fontWeight: 700 }}>
            Money Integrity Failure Lab
          </Typography>
        </Stack>
        <Chip
          icon={<SecurityIcon />}
          label="EPHEMERAL LAB ENVIRONMENT (ISOLATED)"
          color="warning"
          variant="filled"
          sx={{ fontWeight: 700, letterSpacing: 0.5, px: 1 }}
        />
      </Stack>

      <Typography variant="body1" color="text.secondary" sx={{ maxWidth: 880, mb: 2 }}>
        Execute verified production financial services under real-world concurrency and transport faults. Every scenario runs against disposable Testcontainers infrastructure (PostgreSQL 17.11 &amp; Kafka 4.3.1) and audits mathematical invariants via independent direct SQL.
      </Typography>

      <Paper
        variant="outlined"
        sx={{
          p: 1.5,
          bgcolor: 'rgba(255, 152, 0, 0.06)',
          borderColor: 'warning.light',
          borderRadius: 1.5,
          display: 'flex',
          alignItems: 'center',
          gap: 1.5,
        }}
      >
        <Typography variant="caption" color="text.secondary">
          <strong>Safety Guarantee:</strong> Runs are strictly bound to local loopback (<code>127.0.0.1:8083</code>). Zero connection to production or developer databases (<code>ledgerguard_db</code>). Non-destructive to real accounts or balances.
        </Typography>
      </Paper>
    </Box>
  );
};
