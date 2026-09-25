import { Box, Button, Container, Stack, Tab, Tabs, Typography } from '@mui/material';
import { Link as RouterLink, useNavigate, useSearchParams } from 'react-router-dom';
import { useAuth } from '../../auth/hooks/useAuth';
import { RecentTransfersTable } from '../../transfer/components/RecentTransfersTable';
import { FinancialHistory } from '../components/FinancialHistory';
import { FinancialForm } from '../components/FinancialForm';
import { WithdrawalSection } from '../components/WithdrawalSection';
import { FinancialDomain, Funding, Payment, Posted } from '../types';
import { financialApi } from '../api';

export const ActivityPage = ({ domain: routeDomain }: { domain?: FinancialDomain }) => {
  const { user } = useAuth();
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();
  const isCustomer = user?.role === 'CUSTOMER';
  const isMerchant = user?.role === 'MERCHANT';

  // Determine active tab from route prop or search params
  const queryType = searchParams.get('type');
  let activeTab: string;

  if (routeDomain) {
    activeTab = routeDomain;
  } else if (queryType) {
    if (isMerchant) {
      activeTab = queryType === 'payouts' ? 'payouts' : 'payments';
    } else {
      activeTab = ['transfers', 'payments', 'funding', 'payouts'].includes(queryType) ? queryType : 'transfers';
    }
  } else {
    activeTab = isMerchant ? 'payments' : 'transfers';
  }

  const handleTabChange = (_: React.SyntheticEvent, newValue: string) => {
    // Navigate using query parameters on /app/activity to keep URLs stable and persistent
    setSearchParams({ type: newValue });
  };

  const tabs = [
    ...(isCustomer ? [{ value: 'transfers', label: 'Transfers' }] : []),
    { value: 'payments', label: isMerchant ? 'Customer payments' : 'Payments' },
    ...(isCustomer ? [{ value: 'funding', label: 'Funding' }] : []),
    { value: 'payouts', label: 'Payouts' },
  ];

  return (
    <Container maxWidth="lg">
      <Box sx={{ mb: 3 }}>
        <Typography component="h1" variant="h4" color="primary.main" sx={{ fontSize: { xs: '1.75rem', md: '2rem' }, fontWeight: 700, mb: 0.75 }}>
          Financial activity
        </Typography>
        <Typography variant="body2" color="text.secondary">
          Review customer payments, refunds and payout activity.
        </Typography>
      </Box>

      <Tabs
        value={activeTab}
        onChange={handleTabChange}
        aria-label="Financial activity tabs"
        variant="scrollable"
        scrollButtons="auto"
        allowScrollButtonsMobile
        sx={{ mb: 3, borderBottom: '1px solid', borderColor: 'divider' }}
      >
        {tabs.map(tab => (
          <Tab
            key={tab.value}
            value={tab.value}
            label={tab.label}
            sx={{ fontWeight: 600 }}
          />
        ))}
      </Tabs>

      <Stack spacing={3}>
        {/* Merchant Payouts View: Balanced 2-column withdrawal form + history */}
        {isMerchant && activeTab === 'payouts' && (
          <>
            <WithdrawalSection />
            <FinancialHistory domain="payouts" merchant={true} />
          </>
        )}

        {/* Merchant Payments View: Customer payments list with filters, refund visibility */}
        {isMerchant && activeTab === 'payments' && (
          <FinancialHistory domain="payments" merchant={true} />
        )}

        {/* Customer Views */}
        {isCustomer && activeTab === 'transfers' && (
          <>
            <Box><Button component={RouterLink} to="/app" variant="outlined">Send money</Button></Box>
            <RecentTransfersTable />
          </>
        )}

        {isCustomer && activeTab === 'payments' && (
          <>
            <FinancialForm<Posted<Payment>>
              key="customer-payments"
              domain="payments"
              label="Pay merchant"
              merchant
              submit={(payload, key) => financialApi.payMerchant(payload, key)}
              onSuccess={result => navigate(`/app/payments/${result.paymentId}`)}
            />
            <FinancialHistory domain="payments" merchant={false} />
          </>
        )}

        {isCustomer && activeTab === 'funding' && (
          <>
            <FinancialForm<Posted<Funding>>
              key="customer-funding"
              domain="funding"
              label="Add money"
              submit={(payload, key) => financialApi.addMoney({ amountMinor: String(payload.amountMinor) }, key)}
              onSuccess={result => navigate(`/app/funding/${result.fundingId}`)}
            />
            <FinancialHistory domain="funding" merchant={false} />
          </>
        )}

        {isCustomer && activeTab === 'payouts' && (
          <>
            <WithdrawalSection onWithdrawalSuccess={result => navigate(`/app/payouts/${result.payoutId}`)} />
            <FinancialHistory domain="payouts" merchant={false} />
          </>
        )}
      </Stack>
    </Container>
  );
};
