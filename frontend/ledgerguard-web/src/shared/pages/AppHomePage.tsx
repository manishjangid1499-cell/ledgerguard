import { Box, Button, Container, Grid, Paper, Stack, Typography } from '@mui/material';
import ScienceOutlinedIcon from '@mui/icons-material/ScienceOutlined';
import { Link as RouterLink } from 'react-router-dom';
import { useAuth } from '../../auth/hooks/useAuth';
import { WalletCard } from '../../wallet/components/WalletCard';
import { TransferForm } from '../../transfer/components/TransferForm';
import { RecentTransfersTable } from '../../transfer/components/RecentTransfersTable';
import { FinancialHistory } from '../../financial/components/FinancialHistory';

export const AppHomePage = () => {
  const { user } = useAuth();
  const isFinancialUser = user?.role === 'CUSTOMER' || user?.role === 'MERCHANT';
  return (
    <Container maxWidth="lg">
      <Box sx={{ mb: 3 }}>
        <Typography variant="h4" component="h1" color="primary.main" sx={{ fontSize: { xs: '1.75rem', md: '2rem' }, mb: 0.75 }}>
          {user?.role === 'MERCHANT' ? 'Merchant overview' : isFinancialUser ? 'Your wallet' : 'Operations'}
        </Typography>
        <Typography variant="body2" color="text.secondary">
          {user?.role === 'MERCHANT' ? 'Review received payments, manage refunds and withdraw available funds.' : isFinancialUser ? 'Add money, send money, pay Merchants and track your financial activity.' : 'Review financial integrity through controlled resilience scenarios.'}
        </Typography>
      </Box>
      {isFinancialUser ? (
        <>
        <Stack direction="row" useFlexGap spacing={1.5} sx={{ flexWrap: 'wrap', mb: 3 }}>
          {user?.role === 'CUSTOMER' && <Button component={RouterLink} to="/app/funding" variant="contained">Add money</Button>}
          {user?.role === 'MERCHANT' && <Button component={RouterLink} to="/app/payouts" variant="contained">Withdraw</Button>}
          {user?.role === 'CUSTOMER' && (
            <>
              <Button variant="outlined" onClick={() => {
                const input = document.getElementById('transfer-destination');
                input?.scrollIntoView({ block: 'center' }); input?.focus({ preventScroll: true });
              }}>Send money</Button>
              <Button component={RouterLink} to="/app/payments" variant="outlined">Pay merchant</Button>
              <Button component={RouterLink} to="/app/payouts" variant="outlined">Withdraw</Button>
            </>
          )}
        </Stack>
        <Grid container spacing={3}>
          {user?.role === 'CUSTOMER' ? (
            <>
              <Grid size={{ xs: 12, md: 5 }}><WalletCard /></Grid>
              <Grid size={{ xs: 12, md: 7 }}><TransferForm /></Grid>
              <Grid size={12}><RecentTransfersTable /></Grid>
            </>
          ) : (
            <>
              <Grid size={12}><WalletCard /></Grid>
              <Grid size={12}><FinancialHistory domain="payments" merchant /></Grid>
            </>
          )}
        </Grid>
        </>
      ) : (
        <Paper variant="outlined" sx={{ p: { xs: 3, sm: 4 } }}>
          <ScienceOutlinedIcon color="secondary" sx={{ fontSize: 32, mb: 2 }} />
          <Typography component="h2" variant="h6" sx={{ mb: 1 }}>Financial integrity testing</Typography>
          <Typography variant="body2" color="text.secondary" sx={{ maxWidth: 620, mb: 3, lineHeight: 1.7 }}>
            Run isolated fault scenarios and inspect their timelines and invariant checks in the Failure Lab.
          </Typography>
          {user?.role === 'OPS' && <Button component={RouterLink} to="/app/failure-lab" variant="contained">Open Failure Lab</Button>}
        </Paper>
      )}
    </Container>
  );
};
