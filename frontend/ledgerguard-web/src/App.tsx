import React from 'react';
import {
  Container,
  Box,
  Typography,
  Paper,
  Chip,
  Stack,
  Divider,
  createTheme,
  ThemeProvider,
  CssBaseline
} from '@mui/material';
import {
  AccountBalanceWallet as AccountBalanceWalletIcon,
  Security as SecurityIcon,
  CheckCircleOutlined as CheckCircleOutlineIcon
} from '@mui/icons-material';

const theme = createTheme({
  palette: {
    mode: 'light',
    primary: {
      main: '#0d47a1',
    },
    secondary: {
      main: '#00897b',
    },
    background: {
      default: '#f8fafc',
      paper: '#ffffff',
    },
  },
  typography: {
    fontFamily: 'Roboto, sans-serif',
  },
});

export const App: React.FC = () => {
  return (
    <ThemeProvider theme={theme}>
      <CssBaseline />
      <Container maxWidth="md" sx={{ py: 8 }}>
        <Paper elevation={2} sx={{ p: 4, borderRadius: 2 }}>
          <Stack direction="row" spacing={2} sx={{ alignItems: 'center', mb: 2 }}>
            <SecurityIcon color="primary" sx={{ fontSize: 40 }} />
            <Box>
              <Typography variant="h4" component="h1" sx={{ fontWeight: 'bold', color: 'primary.main' }}>
                LedgerGuard
              </Typography>
              <Typography variant="subtitle1" sx={{ color: 'text.secondary' }}>
                Payment Integrity & Ledger Platform
              </Typography>
            </Box>
          </Stack>

          <Divider sx={{ my: 2 }} />

          <Box sx={{ my: 3 }}>
            <Typography variant="h6" gutterBottom>
              Workspace Status: Phase 1 Bootstrapped
            </Typography>
            <Typography variant="body1" sx={{ color: 'text.secondary', mb: 2 }}>
              The frontend shell and backend multi-module workspace are initialized.
              Financial business modules (Ledger, Accounts, Transfers, Payments, Outbox, and Failure Lab)
              will be developed iteratively in subsequent development phases.
            </Typography>
          </Box>

          <Stack direction="row" spacing={1} sx={{ flexWrap: 'wrap', gap: 1 }}>
            <Chip
              icon={<CheckCircleOutlineIcon />}
              label="Java 21 / Spring Boot 4.1.1"
              color="primary"
              variant="outlined"
            />
            <Chip
              icon={<CheckCircleOutlineIcon />}
              label="PostgreSQL Authority"
              color="primary"
              variant="outlined"
            />
            <Chip
              icon={<CheckCircleOutlineIcon />}
              label="React 19 / Vite 8.1 / MUI 9"
              color="secondary"
              variant="outlined"
            />
            <Chip
              icon={<AccountBalanceWalletIcon />}
              label="Immutable Double-Entry Core"
              variant="outlined"
            />
          </Stack>
        </Paper>
      </Container>
    </ThemeProvider>
  );
};

export default App;
