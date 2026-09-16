import { Box, Button, Card, CardContent, Container, Grid, Stack, Typography } from '@mui/material';
import { Link as RouterLink } from 'react-router-dom';
import AccountBalanceWalletOutlinedIcon from '@mui/icons-material/AccountBalanceWalletOutlined';
import SyncAltIcon from '@mui/icons-material/SyncAlt';
import StorefrontOutlinedIcon from '@mui/icons-material/StorefrontOutlined';
import PaymentsOutlinedIcon from '@mui/icons-material/PaymentsOutlined';
import PersonOutlinedIcon from '@mui/icons-material/PersonOutlined';
import CheckCircleOutlinedIcon from '@mui/icons-material/CheckCircleOutlined';
import SecurityIcon from '@mui/icons-material/Security';
import { useAuth } from '../../auth/hooks/useAuth';

const productCapabilities = [
  {
    title: 'Wallet funding',
    icon: AccountBalanceWalletOutlinedIcon,
    copy: 'LedgerGuard supports controlled wallet funding through provider-backed financial workflows.',
  },
  {
    title: 'Wallet transfers',
    icon: SyncAltIcon,
    copy: 'LedgerGuard enables secure funds movement between platform accounts.',
  },
  {
    title: 'Merchant payments',
    icon: StorefrontOutlinedIcon,
    copy: 'LedgerGuard processes customer payments to merchants with accurate fee allocation and refund support.',
  },
  {
    title: 'External payouts',
    icon: PaymentsOutlinedIcon,
    copy: 'LedgerGuard manages external payout requests with balance protection and settlement tracking.',
  },
];

const customerFeatures = [
  'Wallet funding',
  'Peer-to-peer transfers',
  'Merchant payment processing',
  'Refund receipt',
  'External payout requests',
  'Traceable wallet balances',
];

const merchantFeatures = [
  'Payment acceptance',
  'Gross, fee and net allocation',
  'Refund processing',
  'Outbound transfers',
  'Wallet balance management',
  'External payout requests',
];

const trustPrinciples = [
  {
    title: 'Every movement is recorded',
    copy: 'Financial operations are backed by traceable ledger records.',
  },
  {
    title: 'Safe retries',
    copy: 'Retrying the same financial request is protected from creating a second economic effect.',
  },
  {
    title: 'Uncertain outcomes stay pending',
    copy: 'LedgerGuard does not mark an external operation settled until there is reliable evidence of the outcome.',
  },
  {
    title: 'Traceable balances',
    copy: 'Balances can be checked against the financial record that produced them.',
  },
];

const workflowSteps = [
  {
    number: '01',
    title: 'Create an account',
    copy: 'Register a Customer or Merchant profile.',
  },
  {
    number: '02',
    title: 'Fund or receive balances',
    copy: 'Customers can fund their wallets, while merchants can receive LedgerGuard payments.',
  },
  {
    number: '03',
    title: 'Execute financial operations',
    copy: 'Initiate transfers, merchant payments, refunds or payout requests.',
  },
  {
    number: '04',
    title: 'Confirm final settlement',
    copy: 'Balances and operation states reflect confirmed financial outcomes.',
  },
];

const securityHighlights = [
  'Server-enforced account ownership',
  'Protected authentication',
  'Traceable balances',
  'Controlled provider settlement',
];

export const LandingPage = () => {
  const { status } = useAuth();
  const authenticated = status === 'authenticated';

  const accountActions = (
    <Stack direction="row" spacing={1.5} useFlexGap sx={{ justifyContent: 'center', flexWrap: 'wrap' }}>
      <Button
        component={RouterLink}
        to={authenticated ? '/app' : '/register'}
        variant="contained"
        size="large"
        sx={{ px: 3, py: 1.25 }}
      >
        {authenticated ? 'Open dashboard' : 'Create account'}
      </Button>
      {!authenticated && (
        <Button
          component={RouterLink}
          to="/login"
          variant="outlined"
          size="large"
          sx={{ px: 3, py: 1.25 }}
        >
          Sign in
        </Button>
      )}
    </Stack>
  );

  return (
    <Container maxWidth="lg">
      {/* Hero Section */}
      <Box
        component="section"
        aria-labelledby="hero-heading"
        sx={{ textAlign: 'center', py: { xs: 6, md: 8 } }}
      >
        <Typography variant="overline" color="secondary.main" sx={{ fontWeight: 700, letterSpacing: '0.1em' }}>
          Payment Integrity &amp; Ledger Platform
        </Typography>
        <Typography
          id="hero-heading"
          component="h1"
          variant="h3"
          sx={{
            color: 'primary.main',
            fontWeight: 700,
            letterSpacing: '-0.035em',
            lineHeight: 1.15,
            fontSize: { xs: '2.25rem', sm: '3rem', md: '3.5rem' },
            maxWidth: 850,
            mx: 'auto',
            mt: 2,
            mb: 2.5,
          }}
        >
          Move money with financial correctness built in.
        </Typography>
        <Typography
          color="text.secondary"
          sx={{
            maxWidth: 680,
            mx: 'auto',
            lineHeight: 1.75,
            fontSize: { xs: '1rem', md: '1.125rem' },
          }}
        >
          Fund wallets, transfer money, pay merchants and request payouts through a ledger designed to preserve a clear financial record.
        </Typography>
        <Typography
          variant="body2"
          color="text.secondary"
          sx={{
            maxWidth: 640,
            mx: 'auto',
            mt: 1.5,
            mb: 3.5,
            lineHeight: 1.7,
          }}
        >
          LedgerGuard protects financial operations across retries, concurrent requests and uncertain provider outcomes.
        </Typography>
        {accountActions}
      </Box>

      {/* Section 1: Product Capabilities */}
      <Box
        component="section"
        id="product"
        aria-labelledby="product-heading"
        sx={{ pb: { xs: 6, md: 8 } }}
      >
        <Typography id="product-heading" component="h2" variant="h5" color="primary.main" sx={{ mb: 1 }}>
          Core platform capabilities
        </Typography>
        <Typography color="text.secondary" variant="body2" sx={{ mb: 3 }}>
          Financial workflows supported across the platform.
        </Typography>
        <Grid container spacing={2.5}>
          {productCapabilities.map(({ title, icon: Icon, copy }) => (
            <Grid key={title} size={{ xs: 12, sm: 6, lg: 3 }}>
              <Card sx={{ height: '100%' }}>
                <CardContent sx={{ p: 3 }}>
                  <Icon color="secondary" sx={{ fontSize: 30, mb: 2 }} />
                  <Typography component="h3" variant="subtitle1" sx={{ fontWeight: 700, mb: 1 }}>
                    {title}
                  </Typography>
                  <Typography variant="body2" color="text.secondary" sx={{ lineHeight: 1.7 }}>
                    {copy}
                  </Typography>
                </CardContent>
              </Card>
            </Grid>
          ))}
        </Grid>
      </Box>

      {/* Section 2: Customer and Merchant Roles */}
      <Box
        component="section"
        id="roles"
        aria-labelledby="roles-heading"
        sx={{ pb: { xs: 6, md: 8 } }}
      >
        <Typography id="roles-heading" component="h2" variant="h5" color="primary.main" sx={{ mb: 1 }}>
          Built for customers and merchants
        </Typography>
        <Typography color="text.secondary" variant="body2" sx={{ mb: 3 }}>
          Account capabilities tailored to each participant in the financial flow.
        </Typography>
        <Grid container spacing={3}>
          {/* Customer Panel */}
          <Grid size={{ xs: 12, md: 6 }}>
            <Card sx={{ height: '100%', p: 1 }}>
              <CardContent sx={{ p: 3 }}>
                <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center', mb: 1.5 }}>
                  <PersonOutlinedIcon color="secondary" sx={{ fontSize: 26 }} />
                  <Typography component="h3" variant="h6" color="primary.main">
                    For customers
                  </Typography>
                </Stack>
                <Typography variant="body2" color="text.secondary" sx={{ mb: 2.5, lineHeight: 1.6 }}>
                  Personal account capabilities backed by immutable ledger accounting.
                </Typography>
                <Stack spacing={1.5}>
                  {customerFeatures.map((item) => (
                    <Stack key={item} direction="row" spacing={1.25} sx={{ alignItems: 'center' }}>
                      <CheckCircleOutlinedIcon color="secondary" sx={{ fontSize: 18, flexShrink: 0 }} />
                      <Typography variant="body2" color="text.primary">
                        {item}
                      </Typography>
                    </Stack>
                  ))}
                </Stack>
              </CardContent>
            </Card>
          </Grid>

          {/* Merchant Panel */}
          <Grid size={{ xs: 12, md: 6 }}>
            <Card sx={{ height: '100%', p: 1 }}>
              <CardContent sx={{ p: 3 }}>
                <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center', mb: 1.5 }}>
                  <StorefrontOutlinedIcon color="secondary" sx={{ fontSize: 26 }} />
                  <Typography component="h3" variant="h6" color="primary.main">
                    For merchants
                  </Typography>
                </Stack>
                <Typography variant="body2" color="text.secondary" sx={{ mb: 2.5, lineHeight: 1.6 }}>
                  Merchant account capabilities with accurate commercial fee accounting.
                </Typography>
                <Stack spacing={1.5}>
                  {merchantFeatures.map((item) => (
                    <Stack key={item} direction="row" spacing={1.25} sx={{ alignItems: 'center' }}>
                      <CheckCircleOutlinedIcon color="secondary" sx={{ fontSize: 18, flexShrink: 0 }} />
                      <Typography variant="body2" color="text.primary">
                        {item}
                      </Typography>
                    </Stack>
                  ))}
                </Stack>
              </CardContent>
            </Card>
          </Grid>
        </Grid>
      </Box>

      {/* Section 3: Financial Correctness */}
      <Box
        component="section"
        aria-labelledby="correctness-heading"
        sx={{
          p: { xs: 3, md: 4 },
          mb: { xs: 6, md: 8 },
          bgcolor: 'background.paper',
          border: '1px solid',
          borderColor: 'divider',
          borderRadius: 2,
        }}
      >
        <Grid container spacing={{ xs: 3, md: 5 }}>
          <Grid size={{ xs: 12, md: 4 }}>
            <Typography variant="overline" color="secondary.main" sx={{ fontWeight: 700 }}>
              Financial correctness
            </Typography>
            <Typography
              id="correctness-heading"
              component="h2"
              variant="h5"
              color="primary.main"
              sx={{ mt: 0.75, mb: 1.5 }}
            >
              Every financial operation should have one clear outcome.
            </Typography>
            <Typography variant="body2" color="text.secondary" sx={{ lineHeight: 1.7 }}>
              Every operation is verified against a single, unbroken financial record.
            </Typography>
          </Grid>
          <Grid size={{ xs: 12, md: 8 }}>
            <Grid container spacing={3}>
              {trustPrinciples.map(({ title, copy }) => (
                <Grid key={title} size={{ xs: 12, sm: 6 }}>
                  <Stack direction="row" spacing={1.25} sx={{ alignItems: 'flex-start' }}>
                    <CheckCircleOutlinedIcon color="secondary" sx={{ fontSize: 20, mt: 0.25, flexShrink: 0 }} />
                    <Box>
                      <Typography component="h3" variant="subtitle2" sx={{ mb: 0.5 }}>
                        {title}
                      </Typography>
                      <Typography variant="body2" color="text.secondary" sx={{ lineHeight: 1.7 }}>
                        {copy}
                      </Typography>
                    </Box>
                  </Stack>
                </Grid>
              ))}
            </Grid>
          </Grid>
        </Grid>
      </Box>

      {/* Section 4: How It Works */}
      <Box
        component="section"
        id="how-it-works"
        aria-labelledby="how-it-works-heading"
        sx={{ pb: { xs: 6, md: 8 } }}
      >
        <Typography id="how-it-works-heading" component="h2" variant="h5" color="primary.main" sx={{ mb: 1 }}>
          How LedgerGuard works
        </Typography>
        <Typography color="text.secondary" variant="body2" sx={{ mb: 3 }}>
          A straightforward workflow from account setup to confirmed settlement.
        </Typography>
        <Grid container spacing={2.5}>
          {workflowSteps.map(({ number, title, copy }) => (
            <Grid key={number} size={{ xs: 12, sm: 6, lg: 3 }}>
              <Card sx={{ height: '100%' }}>
                <CardContent sx={{ p: 3 }}>
                  <Typography
                    variant="caption"
                    color="secondary.main"
                    sx={{
                      fontWeight: 700,
                      letterSpacing: '0.05em',
                      display: 'inline-block',
                      px: 1,
                      py: 0.25,
                      bgcolor: 'background.default',
                      borderRadius: 1,
                      border: '1px solid',
                      borderColor: 'divider',
                      mb: 2,
                    }}
                  >
                    Step {number}
                  </Typography>
                  <Typography component="h3" variant="subtitle1" sx={{ fontWeight: 700, mb: 1 }}>
                    {title}
                  </Typography>
                  <Typography variant="body2" color="text.secondary" sx={{ lineHeight: 1.7 }}>
                    {copy}
                  </Typography>
                </CardContent>
              </Card>
            </Grid>
          ))}
        </Grid>
      </Box>

      {/* Section 5: Security & Reliability */}
      <Box
        component="section"
        id="security"
        aria-labelledby="security-heading"
        sx={{
          p: { xs: 3, md: 4 },
          mb: { xs: 6, md: 8 },
          border: '1px solid',
          borderColor: 'divider',
          bgcolor: 'background.paper',
          borderRadius: 2,
        }}
      >
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2} sx={{ alignItems: 'flex-start', mb: 3 }}>
          <SecurityIcon color="secondary" sx={{ fontSize: 32, mt: 0.5, flexShrink: 0 }} />
          <Box>
            <Typography id="security-heading" component="h2" variant="h6" sx={{ mb: 0.75 }}>
              Designed for trustworthy financial operations
            </Typography>
            <Typography variant="body2" color="text.secondary" sx={{ lineHeight: 1.7 }}>
              Account ownership and financial permissions are enforced by the server, while external operations remain pending until their outcome is confirmed.
            </Typography>
          </Box>
        </Stack>
        <Grid container spacing={2}>
          {securityHighlights.map((item) => (
            <Grid key={item} size={{ xs: 12, sm: 6, md: 3 }}>
              <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
                <CheckCircleOutlinedIcon color="secondary" sx={{ fontSize: 18, flexShrink: 0 }} />
                <Typography variant="body2" color="text.primary" sx={{ fontWeight: 500 }}>
                  {item}
                </Typography>
              </Stack>
            </Grid>
          ))}
        </Grid>
      </Box>

      {/* Section 6: Final CTA */}
      <Box
        component="section"
        aria-labelledby="cta-heading"
        sx={{ py: { xs: 5, md: 7 }, textAlign: 'center' }}
      >
        <Typography id="cta-heading" component="h2" variant="h5" color="primary.main" sx={{ mb: 1 }}>
          Start using LedgerGuard
        </Typography>
        <Typography variant="body2" color="text.secondary" sx={{ maxWidth: 540, mx: 'auto', mb: 3.5, lineHeight: 1.7 }}>
          Create an account to explore LedgerGuard and access platform financial operations.
        </Typography>
        {accountActions}
      </Box>
    </Container>
  );
};
