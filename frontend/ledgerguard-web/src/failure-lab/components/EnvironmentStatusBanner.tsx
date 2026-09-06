import React from 'react';
import { Alert, AlertTitle, Box, Chip, Paper, Stack, Typography, CircularProgress } from '@mui/material';
import CheckCircleIcon from '@mui/icons-material/CheckCircle';
import StorageIcon from '@mui/icons-material/Storage';
import HubIcon from '@mui/icons-material/Hub';
import HttpIcon from '@mui/icons-material/Http';
import { useLabEnvironment } from '../hooks/useFailureLab';

export const EnvironmentStatusBanner: React.FC = () => {
  const { data: env, isLoading, isError } = useLabEnvironment();

  if (isLoading) {
    return (
      <Paper variant="outlined" sx={{ p: 2, mb: 3 }}>
        <Stack direction="row" spacing={2} sx={{ alignItems: 'center' }}>
          <CircularProgress size={20} />
          <Typography variant="body2" color="text.secondary">
            Connecting to Failure Lab runtime on 127.0.0.1:8083...
          </Typography>
        </Stack>
      </Paper>
    );
  }

  if (isError || !env || env.status === 'DISABLED') {
    return (
      <Alert severity="warning" variant="outlined" sx={{ mb: 3, bgcolor: 'background.paper' }}>
        <AlertTitle sx={{ fontWeight: 700 }}>Failure Lab Backend Inactive</AlertTitle>
        <Typography variant="body2" sx={{ mb: 1 }}>
          The local Failure Lab backend is currently offline or disabled. To enable interactive chaos execution on loopback port <code>8083</code>, run:
        </Typography>
        <Box
          component="pre"
          sx={{
            p: 1.5,
            bgcolor: 'grey.900',
            color: 'grey.100',
            borderRadius: 1,
            fontSize: '0.82rem',
            overflowX: 'auto',
            m: 0,
          }}
        >
          .\mvnw.cmd -pl backend/failure-lab spring-boot:run &quot;-Dspring-boot.run.arguments=--ledgerguard.lab.enabled=true&quot;
        </Box>
      </Alert>
    );
  }

  return (
    <Paper variant="outlined" sx={{ p: 2, mb: 3, bgcolor: 'background.paper' }}>
      <Stack
        direction={{ xs: 'column', md: 'row' }}
        spacing={2}
        sx={{ justifyContent: 'space-between', alignItems: { xs: 'flex-start', md: 'center' } }}
      >
        <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center' }}>
          <CheckCircleIcon color="success" fontSize="small" />
          <Typography variant="subtitle2" sx={{ fontWeight: 600 }}>
            Target Infrastructure: Ephemeral Testcontainers
          </Typography>
        </Stack>

        <Stack direction="row" spacing={1.5} sx={{ flexWrap: 'wrap' }}>
          <Chip
            icon={<StorageIcon fontSize="small" />}
            label={`PostgreSQL 17.11: ${env.postgresStatus}`}
            size="small"
            color={env.postgresStatus === 'UP' ? 'success' : 'default'}
            variant="outlined"
          />
          <Chip
            icon={<HubIcon fontSize="small" />}
            label={`Kafka 4.3.1: ${env.kafkaStatus}`}
            size="small"
            color={env.kafkaStatus === 'UP' ? 'success' : 'default'}
            variant="outlined"
          />
          <Chip
            icon={<HttpIcon fontSize="small" />}
            label={`Mock PSP Adapter: ${env.pspAdapterStatus}`}
            size="small"
            color={env.pspAdapterStatus === 'UP' ? 'success' : 'default'}
            variant="outlined"
          />
        </Stack>
      </Stack>
    </Paper>
  );
};
