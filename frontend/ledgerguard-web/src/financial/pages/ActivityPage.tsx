import { useEffect } from 'react';
import { Box, Button, Card, CardContent, Container, Stack, Tab, Tabs, Typography } from '@mui/material';
import { Link as RouterLink, useNavigate } from 'react-router-dom';
import { useAuth } from '../../auth/hooks/useAuth';
import { RecentTransfersTable } from '../../transfer/components/RecentTransfersTable';
import { FinancialHistory } from '../components/FinancialHistory';
import { FinancialForm } from '../components/FinancialForm';
import { FinancialDomain, Funding, Payout, Payment, Posted } from '../types';
import { financialApi } from '../api';

export const ActivityPage = ({ domain }: { domain?: FinancialDomain }) => {
  const { user } = useAuth();
  const navigate = useNavigate();
  const customer = user?.role === 'CUSTOMER';
  const merchant = user?.role === 'MERCHANT';

  const effectiveDomain: FinancialDomain | undefined =
    merchant && (!domain || (domain as string) === 'transfers') ? 'payments' : domain;

  useEffect(() => {
    if (merchant && (!domain || (domain as string) === 'transfers')) {
      navigate('/app/payments', { replace: true });
    }
  }, [merchant, domain, navigate]);

  const tabs = [
    ...(customer ? [{ value: 'transfers', title: 'Transfers', to: '/app/activity' }] : []),
    { value: 'payments', title: merchant ? 'Customer payments' : 'Payments', to: '/app/payments' },
    ...(customer ? [{ value: 'funding', title: 'Funding', to: '/app/funding' }] : []),
    { value: 'payouts', title: 'Payouts', to: '/app/payouts' },
  ];
  const create = effectiveDomain === 'payouts' || (customer && (effectiveDomain === 'funding' || effectiveDomain === 'payments'));
  const label = effectiveDomain === 'funding' ? 'Add money' : effectiveDomain === 'payments' ? 'Pay merchant' : 'Withdraw';
  return <Container maxWidth="lg">
    <Typography component="h1" variant="h4" color="primary.main" sx={{ fontSize: { xs: '1.75rem', md: '2rem' }, mb: 1 }}>Activity</Typography>
    <Typography variant="body2" color="text.secondary" sx={{ mb: 3 }}>Review each financial workflow and its recorded outcome.</Typography>
    <Tabs value={effectiveDomain || 'transfers'} aria-label="Financial activity" variant="scrollable" scrollButtons="auto" allowScrollButtonsMobile sx={{ mb: 3, borderBottom: '1px solid', borderColor: 'divider' }}>
      {tabs.map(tab => <Tab component={RouterLink} to={tab.to} key={tab.value} value={tab.value} label={tab.title} />)}
    </Tabs>
    <Stack spacing={3}>
      {create && effectiveDomain && <Card sx={{ maxWidth: 620 }}><CardContent sx={{ p: { xs: 2.5, sm: 3 } }}>
        <Typography component="h2" variant="h6" sx={{ mb: 1 }}>{label}</Typography>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 3, lineHeight: 1.7 }}>
          {effectiveDomain === 'funding' ? 'Request external funding for your wallet. Funds become available after confirmed settlement.'
            : effectiveDomain === 'payments' ? 'Pay a Merchant using their LedgerGuard merchant wallet ID.'
            : 'Create an external payout request. Funds are reserved while provider confirmation is pending.'}
        </Typography>
        {effectiveDomain === 'payments' ? <FinancialForm<Posted<Payment>> key={effectiveDomain} domain={effectiveDomain} label={label} merchant
          submit={(payload, key) => financialApi.payMerchant(payload, key)}
          onSuccess={result => navigate(`/app/payments/${result.paymentId}`)} /> :
          <FinancialForm<Posted<Funding | Payout>> key={effectiveDomain} domain={effectiveDomain} label={label}
            submit={(payload, key) => effectiveDomain === 'funding' ? financialApi.addMoney({ amountMinor: String(payload.amountMinor) }, key)
              : financialApi.withdraw({ amountMinor: String(payload.amountMinor) }, key)}
            onSuccess={result => navigate(`/app/${effectiveDomain}/${'fundingId' in result ? result.fundingId : result.payoutId}`)} />}
      </CardContent></Card>}
      {effectiveDomain ? <FinancialHistory key={effectiveDomain} domain={effectiveDomain} merchant={merchant} /> : <>
        {customer && <Box><Button component={RouterLink} to="/app" variant="outlined">Send money</Button></Box>}
        <RecentTransfersTable />
      </>}
    </Stack>
  </Container>;
};
