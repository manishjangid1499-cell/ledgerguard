import { Box, Button, Card, CardContent, Container, Grid, Stack, Typography } from '@mui/material';
import { Link as RouterLink } from 'react-router-dom';
import AccountBalanceOutlinedIcon from '@mui/icons-material/AccountBalanceOutlined';
import SyncAltIcon from '@mui/icons-material/SyncAlt';
import HubOutlinedIcon from '@mui/icons-material/HubOutlined';
import FactCheckOutlinedIcon from '@mui/icons-material/FactCheckOutlined';
import CheckCircleOutlineIcon from '@mui/icons-material/CheckCircle';
import SecurityIcon from '@mui/icons-material/Security';
import { PlatformArchitecture } from '../components/PlatformArchitecture';
import { useAuth } from '../../auth/hooks/useAuth';

const capabilities = [
  { title: 'Immutable Double-Entry', icon: AccountBalanceOutlinedIcon,
    copy: 'Every financial movement is recorded through balanced debit and credit journal entries, preserving an auditable source of truth.' },
  { title: 'Payment Integrity', icon: SyncAltIcon,
    copy: 'Transfers, payments, refunds, funding and payouts use idempotent processing, controlled state transitions and balance protection.' },
  { title: 'Reliable Event Delivery', icon: HubOutlinedIcon,
    copy: 'Transactional outbox processing keeps committed financial state and asynchronous event publication consistent.' },
  { title: 'Reconciliation & Recovery', icon: FactCheckOutlinedIcon,
    copy: 'Reconciliation compares journal records, balance snapshots and provider outcomes to identify discrepancies and guide recovery.' },
];

const safeguards = [
  ['Immutable history', 'Posted journal records preserve the history of each financial movement.'],
  ['Traceable balances', 'Balance snapshots are derived from the journal and can be checked against it.'],
  ['Safe repeat requests', 'Idempotency prevents the same operation from moving money twice.'],
  ['Explicit uncertainty', 'An unknown provider outcome stays unresolved until there is evidence to settle it.'],
];

export const LandingPage = () => {
  const { status } = useAuth();
  const authenticated = status === 'authenticated';
  const accountActions = (
    <Stack direction="row" spacing={1.5} useFlexGap sx={{ justifyContent: 'center', flexWrap: 'wrap' }}>
      <Button component={RouterLink} to={authenticated ? '/app' : '/register'} variant="contained" size="large" sx={{ px: 3, py: 1.25 }}>
        {authenticated ? 'Open dashboard' : 'Create account'}
      </Button>
      {!authenticated && <Button component={RouterLink} to="/login" variant="outlined" size="large" sx={{ px: 3, py: 1.25 }}>Sign in</Button>}
    </Stack>
  );

  return (
    <Container maxWidth="lg">
      <Box component="section" aria-labelledby="hero-heading" sx={{ textAlign: 'center', py: { xs: 6, md: 8 } }}>
        <Typography variant="overline" color="secondary.main" sx={{ fontWeight: 700, letterSpacing: '0.1em' }}>
          Payment Integrity &amp; Ledger Platform
        </Typography>
        <Typography id="hero-heading" component="h1" variant="h3"
          sx={{ color: 'primary.main', fontWeight: 700, letterSpacing: '-0.035em', lineHeight: 1.12,
            fontSize: { xs: '2.25rem', sm: '3rem', md: '3.5rem' }, maxWidth: 850, mx: 'auto', mt: 2, mb: 2.5 }}>
          Correctness-First<br /> Financial Infrastructure
        </Typography>
        <Typography color="text.secondary" sx={{ maxWidth: 680, mx: 'auto', lineHeight: 1.75, fontSize: { xs: '1rem', md: '1.125rem' } }}>
          Reliable payments. Immutable accounting. LedgerGuard is a payment integrity and ledger platform built to preserve financial truth.
        </Typography>
        <Typography variant="body2" color="text.secondary" sx={{ maxWidth: 640, mx: 'auto', mt: 1.5, mb: 3.5, lineHeight: 1.7 }}>
          Designed for concurrent transactions, repeat requests, asynchronous processing and uncertain provider outcomes.
        </Typography>
        {accountActions}
      </Box>

      <Box component="section" id="platform" aria-labelledby="platform-heading" sx={{ pb: { xs: 6, md: 7 } }}>
        <Typography id="platform-heading" component="h2" variant="h5" color="primary.main" sx={{ mb: 1 }}>Integrity at every step</Typography>
        <Typography color="text.secondary" variant="body2" sx={{ mb: 3 }}>A consistent financial foundation, from the first request to the final record.</Typography>
        <Grid container spacing={2.5}>
          {capabilities.map(({ title, icon: Icon, copy }) => (
            <Grid key={title} size={{ xs: 12, sm: 6, lg: 3 }}>
              <Card sx={{ height: '100%' }}>
                <CardContent sx={{ p: 3 }}>
                  <Icon color="secondary" sx={{ fontSize: 30, mb: 2 }} />
                  <Typography component="h3" variant="subtitle1" sx={{ fontWeight: 700, mb: 1 }}>{title}</Typography>
                  <Typography variant="body2" color="text.secondary" sx={{ lineHeight: 1.7 }}>{copy}</Typography>
                </CardContent>
              </Card>
            </Grid>
          ))}
        </Grid>
      </Box>

      <Box component="section" aria-labelledby="truth-heading"
        sx={{ p: { xs: 3, md: 4 }, mb: { xs: 6, md: 7 }, bgcolor: 'background.paper', border: '1px solid', borderColor: 'divider', borderRadius: 2 }}>
        <Grid container spacing={{ xs: 3, md: 5 }}>
          <Grid size={{ xs: 12, md: 4 }}>
            <Typography variant="overline" color="secondary.main" sx={{ fontWeight: 700 }}>Financial correctness</Typography>
            <Typography id="truth-heading" component="h2" variant="h5" color="primary.main" sx={{ mt: 0.75, mb: 2 }}>One financial source of truth</Typography>
            <Typography variant="body2" color="text.secondary" sx={{ lineHeight: 1.7 }}>Every posted journal transaction preserves balanced debits and credits.</Typography>
          </Grid>
          <Grid size={{ xs: 12, md: 8 }}>
            <Grid container spacing={3}>
              {safeguards.map(([title, copy]) => (
                <Grid key={title} size={{ xs: 12, sm: 6 }}>
                  <Stack direction="row" spacing={1.25} sx={{ alignItems: 'flex-start' }}>
                    <CheckCircleOutlineIcon color="secondary" sx={{ fontSize: 20, mt: 0.25 }} />
                    <Box>
                      <Typography component="h3" variant="subtitle2" sx={{ mb: 0.5 }}>{title}</Typography>
                      <Typography variant="body2" color="text.secondary" sx={{ lineHeight: 1.7 }}>{copy}</Typography>
                    </Box>
                  </Stack>
                </Grid>
              ))}
            </Grid>
          </Grid>
        </Grid>
      </Box>

      <PlatformArchitecture />

      <Box component="section" id="security" aria-labelledby="security-heading"
        sx={{ display: 'flex', alignItems: 'flex-start', gap: 2, borderTop: '1px solid', borderBottom: '1px solid', borderColor: 'divider', py: 3 }}>
        <SecurityIcon color="secondary" sx={{ fontSize: 30, mt: 0.5 }} />
        <Box>
          <Typography id="security-heading" component="h2" variant="h6" sx={{ mb: 0.5 }}>Protected access, clear boundaries</Typography>
          <Typography variant="body2" color="text.secondary" sx={{ lineHeight: 1.7 }}>
            Role-based access control, short-lived access tokens and protected session renewal. Account ownership and permissions are enforced by the server.
          </Typography>
        </Box>
      </Box>

      <Box component="section" aria-labelledby="cta-heading" sx={{ py: { xs: 5, md: 6 }, textAlign: 'center' }}>
        <Typography id="cta-heading" component="h2" variant="h5" color="primary.main" sx={{ mb: 1 }}>Explore LedgerGuard</Typography>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 3 }}>Access your wallet, transfer funds and trace each transfer to its journal record.</Typography>
        {accountActions}
      </Box>
    </Container>
  );
};
