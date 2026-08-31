import React from 'react';
import { Container, Box, Typography, Button, Stack, Card, CardContent, Grid } from '@mui/material';
import { Link as RouterLink } from 'react-router-dom';
import SecurityIcon from '@mui/icons-material/Security';
import AccountBalanceWalletIcon from '@mui/icons-material/AccountBalanceWallet';
import SyncAltIcon from '@mui/icons-material/SyncAlt';
import CheckCircleIcon from '@mui/icons-material/CheckCircle';

export const LandingPage: React.FC = () => {
  return (
    <Container maxWidth="lg">
      <Box sx={{ textAlign: 'center', py: { xs: 4, md: 8 } }}>
        <Typography
          variant="overline"
          sx={{ color: 'secondary.main', fontWeight: 700, letterSpacing: '0.1em' }}
        >
          Payment Integrity & Ledger Platform
        </Typography>
        <Typography
          variant="h3"
          component="h1"
          sx={{
            fontWeight: 800,
            color: 'primary.main',
            mt: 1,
            mb: 2,
            fontSize: { xs: '2rem', sm: '2.75rem', md: '3.25rem' },
          }}
        >
          Correctness-First Financial Infrastructure
        </Typography>
        <Typography
          variant="body1"
          color="text.secondary"
          sx={{ maxWidth: 680, mx: 'auto', mb: 4, fontSize: '1.1rem', lineHeight: 1.6 }}
        >
          LedgerGuard is an immutable double-entry accounting engine designed for high-concurrency payment integrity,
          transactional outbox messaging, and rigorous three-level reconciliation.
        </Typography>
        <Stack direction="row" spacing={2} sx={{ justifyContent: 'center' }}>
          <Button
            component={RouterLink}
            to="/register"
            variant="contained"
            color="primary"
            size="large"
            sx={{ px: 3.5, py: 1.2 }}
          >
            Get Started
          </Button>
          <Button
            component={RouterLink}
            to="/login"
            variant="outlined"
            color="primary"
            size="large"
            sx={{ px: 3.5, py: 1.2 }}
          >
            Sign in
          </Button>
        </Stack>
      </Box>

      <Grid container spacing={3} sx={{ mt: 2, mb: 6 }}>
        <Grid size={{ xs: 12, md: 4 }}>
          <Card sx={{ height: '100%' }}>
            <CardContent sx={{ p: 3 }}>
              <SecurityIcon color="primary" sx={{ fontSize: 36, mb: 1.5 }} />
              <Typography variant="h6" component="h2" gutterBottom sx={{ fontWeight: 600 }}>
                Identity & Security
              </Typography>
              <Typography variant="body2" color="text.secondary" sx={{ lineHeight: 1.6 }}>
                Stateless HS256 JWT access tokens paired with single-use opaque refresh tokens stored in HttpOnly cookies
                and protected with pessimistic database locking.
              </Typography>
            </CardContent>
          </Card>
        </Grid>

        <Grid size={{ xs: 12, md: 4 }}>
          <Card sx={{ height: '100%' }}>
            <CardContent sx={{ p: 3 }}>
              <AccountBalanceWalletIcon color="secondary" sx={{ fontSize: 36, mb: 1.5 }} />
              <Typography variant="h6" component="h2" gutterBottom sx={{ fontWeight: 600 }}>
                Immutable Double-Entry
              </Typography>
              <Typography variant="body2" color="text.secondary" sx={{ lineHeight: 1.6 }}>
                Authoritative double-entry financial ledger backed by PostgreSQL ACID transactions. Balanced debits and
                credits with absolute mathematical integrity.
              </Typography>
            </CardContent>
          </Card>
        </Grid>

        <Grid size={{ xs: 12, md: 4 }}>
          <Card sx={{ height: '100%' }}>
            <CardContent sx={{ p: 3 }}>
              <SyncAltIcon color="primary" sx={{ fontSize: 36, mb: 1.5 }} />
              <Typography variant="h6" component="h2" gutterBottom sx={{ fontWeight: 600 }}>
                Transactional Outbox
              </Typography>
              <Typography variant="body2" color="text.secondary" sx={{ lineHeight: 1.6 }}>
                Post-commit asynchronous event publishing to Apache Kafka in KRaft mode via non-blocking SKIP LOCKED row
                claiming for reliable downstream event delivery.
              </Typography>
            </CardContent>
          </Card>
        </Grid>
      </Grid>

      <Box
        sx={{
          p: 3,
          border: '1px solid',
          borderColor: 'divider',
          borderRadius: 2,
          bgcolor: 'background.paper',
          mb: 6,
        }}
      >
        <Stack direction="row" spacing={1} sx={{ alignItems: 'center', mb: 1 }}>
          <CheckCircleIcon color="secondary" fontSize="small" />
          <Typography variant="subtitle2" sx={{ fontWeight: 600 }}>
            Phase 5 Milestone: Frontend Shell & Secure Authentication
          </Typography>
        </Stack>
        <Typography variant="body2" color="text.secondary">
          The security and identity foundation is active. In-memory JWT access token lifecycle, HttpOnly refresh cookie
          session restoration, and server-authorized RBAC are fully operational.
        </Typography>
      </Box>
    </Container>
  );
};
