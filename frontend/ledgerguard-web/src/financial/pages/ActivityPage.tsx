import { useEffect } from 'react';
import { Box, Container, Stack, Tab, Tabs, Typography } from '@mui/material';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { useAuth } from '../../auth/hooks/useAuth';
import { TransferForm } from '../../transfer/components/TransferForm';
import { RecentTransfersTable } from '../../transfer/components/RecentTransfersTable';
import { FinancialHistory } from '../components/FinancialHistory';
import { WithdrawalSection } from '../components/WithdrawalSection';
import { CustomerPaymentForm } from '../components/CustomerPaymentForm';
import { CustomerFundingForm } from '../components/CustomerFundingForm';
import { FinancialDomain } from '../types';

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
    if (isCustomer && routeDomain === 'payouts') {
      activeTab = 'withdrawals';
    } else {
      activeTab = routeDomain;
    }
  } else if (queryType) {
    if (isMerchant) {
      activeTab = queryType === 'payouts' || queryType === 'withdrawals' ? 'payouts' : 'payments';
    } else {
      if (queryType === 'payouts') {
        activeTab = 'withdrawals';
      } else if (['transfers', 'payments', 'funding', 'withdrawals'].includes(queryType)) {
        activeTab = queryType;
      } else {
        activeTab = 'transfers';
      }
    }
  } else {
    activeTab = isMerchant ? 'payments' : 'transfers';
  }

  // Canonicalize payouts alias to withdrawals for customer without polluting history
  useEffect(() => {
    if (isCustomer && queryType === 'payouts') {
      setSearchParams({ type: 'withdrawals' }, { replace: true });
    }
  }, [isCustomer, queryType, setSearchParams]);

  const handleTabChange = (_: React.SyntheticEvent, newValue: string) => {
    setSearchParams({ type: newValue });
  };

  const tabs = [
    ...(isCustomer ? [{ value: 'transfers', label: 'Transfers' }] : []),
    { value: 'payments', label: isMerchant ? 'Customer payments' : 'Payments' },
    ...(isCustomer ? [{ value: 'funding', label: 'Funding' }] : []),
    { value: isCustomer ? 'withdrawals' : 'payouts', label: isCustomer ? 'Withdrawals' : 'Payouts' },
  ];

  return (
    <Container maxWidth="lg">
      <Box sx={{ mb: 3 }}>
        <Typography
          component="h1"
          variant="h4"
          color="primary.main"
          sx={{ fontSize: { xs: '1.75rem', md: '2rem' }, fontWeight: 700, mb: 0.75 }}
        >
          Financial activity
        </Typography>
        <Typography variant="body2" color="text.secondary">
          {isMerchant
            ? 'Review customer payments, refunds and payout activity.'
            : 'Review transfers, merchant payments, wallet funding and withdrawals.'}
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
            <WithdrawalSection onWithdrawalSuccess={result => navigate(`/app/payouts/${result.payoutId}`)} />
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
            <TransferForm />
            <RecentTransfersTable />
          </>
        )}

        {isCustomer && activeTab === 'payments' && (
          <>
            <CustomerPaymentForm onSuccess={result => navigate(`/app/payments/${result.paymentId}`)} />
            <FinancialHistory domain="payments" merchant={false} />
          </>
        )}

        {isCustomer && activeTab === 'funding' && (
          <>
            <CustomerFundingForm onSuccess={result => navigate(`/app/funding/${result.fundingId}`)} />
            <FinancialHistory domain="funding" merchant={false} />
          </>
        )}

        {isCustomer && activeTab === 'withdrawals' && (
          <>
            <WithdrawalSection onWithdrawalSuccess={result => navigate(`/app/payouts/${result.payoutId}`)} />
            <FinancialHistory domain="payouts" merchant={false} />
          </>
        )}
      </Stack>
    </Container>
  );
};
