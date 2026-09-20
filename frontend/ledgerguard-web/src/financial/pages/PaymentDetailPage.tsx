import { useEffect, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Alert, Button, Card, CardContent, Container, Grid, Stack, Typography } from '@mui/material';
import ArrowBackIcon from '@mui/icons-material/ArrowBack';
import RefreshIcon from '@mui/icons-material/Refresh';
import { Link as RouterLink, useParams } from 'react-router-dom';
import { financialApi } from '../api';
import { financialError } from '../feedback';
import { FinancialForm } from '../components/FinancialForm';
import { RecordFields } from '../components/RecordFields';
import { RefundHistory } from '../components/RefundHistory';
import { useAuth } from '../../auth/hooks/useAuth';
import { DataLoading } from '../../shared/components/DataLoading';
import { StatusBadge } from '../../shared/components/StatusBadge';
import { AmountDisplay } from '../../shared/components/AmountDisplay';
import { WalletCard } from '../../wallet/components/WalletCard';
import { formatMinorUnitsToInr } from '../../shared/utils/money';
import { formatDateTime } from '../../shared/utils/display';

export const PaymentDetailPage = () => {
  const { paymentId = '' } = useParams();
  const { user } = useAuth();
  const queryClient = useQueryClient();
  const [refundPage, setRefundPage] = useState(0);
  const [refunding, setRefunding] = useState(false);
  const [refunded, setRefunded] = useState(false);
  const query = useQuery({ queryKey: ['payments', 'detail', paymentId, refundPage],
    queryFn: () => financialApi.paymentDetail(paymentId, refundPage), enabled: Boolean(paymentId), staleTime: 0 });
  const detail = query.data;
  const payment = detail?.payment;
  const refundedAmount = detail?.refundedAmountMinor;
  useEffect(() => {
    if (refundedAmount !== undefined) void queryClient.invalidateQueries({ queryKey: ['wallet'] });
  }, [paymentId, refundedAmount, queryClient]);
  const eligible = user?.role === 'MERCHANT' && payment?.status === 'SUCCEEDED'
    && detail && /^\d+$/.test(detail.refundableAmountMinor) && BigInt(detail.refundableAmountMinor) > 0n;
  return <Container maxWidth="lg">
    <Button component={RouterLink} to="/app/payments" startIcon={<ArrowBackIcon />} sx={{ mb: 2 }}>Back to payments</Button>
    <Stack direction="row" useFlexGap spacing={2} sx={{ justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', mb: 3 }}>
      <Typography component="h1" variant="h4" color="primary.main" sx={{ fontSize: { xs: '1.75rem', md: '2rem' } }}>Payment details</Typography>
      <Button variant="outlined" startIcon={<RefreshIcon />} disabled={query.isFetching} onClick={() => { void query.refetch(); }}>Refresh payment</Button>
    </Stack>
    {query.isError && <Alert severity="error" sx={{ mb: 3 }}>{financialError(query.error)}</Alert>}
    {query.isLoading ? <DataLoading label="Loading payment details" minHeight={360} /> : detail && payment &&
      <Grid container spacing={3}>
        <Grid size={{ xs: 12, md: 7 }}><Stack spacing={3}>
          <Card><CardContent sx={{ p: { xs: 2.5, sm: 3 } }}>
            <Typography variant="body2" color="text.secondary">Gross payment · {payment.currency}</Typography>
            <AmountDisplay amount={payment.grossAmountMinor} />
            <StatusBadge status={payment.status} />
            <RecordFields fields={[
              { label: 'Platform fee', value: formatMinorUnitsToInr(payment.feeAmountMinor) },
              { label: 'Merchant net amount', value: formatMinorUnitsToInr(payment.merchantNetAmountMinor) },
              { label: 'Refunded amount', value: formatMinorUnitsToInr(detail.refundedAmountMinor) },
              { label: 'Remaining refundable amount', value: formatMinorUnitsToInr(detail.refundableAmountMinor) },
              { label: 'Payment ID', value: payment.paymentId, copy: true },
              { label: 'Customer wallet ID', value: payment.customerLedgerAccountId, copy: true },
              { label: 'Merchant wallet ID', value: payment.merchantLedgerAccountId, copy: true },
              { label: 'Created', value: formatDateTime(payment.createdAt) },
              ...(payment.completedAt ? [{ label: 'Completed', value: formatDateTime(payment.completedAt) }] : []),
              ...(payment.journalTransactionId ? [{ label: 'Journal transaction ID', value: payment.journalTransactionId, copy: true }] : []),
            ]} />
            {eligible && !refunding && <Button variant="outlined" sx={{ mt: 3 }} onClick={() => { setRefunding(true); setRefunded(false); }}>Refund payment</Button>}
          </CardContent></Card>
          {refunded && <Alert severity="success">The refund was posted. Payment records and your wallet are being refreshed.</Alert>}
          {refunding && <Card><CardContent sx={{ p: { xs: 2.5, sm: 3 } }}>
            <Typography component="h2" variant="h6" sx={{ mb: 1 }}>Issue a refund</Typography>
            <Typography variant="body2" color="text.secondary" sx={{ mb: 3 }}>Enter a partial amount or use the full remaining amount. Refund eligibility and fee reversal are determined when the request is processed.</Typography>
            <FinancialForm domain="payments" label="Issue refund" refundableAmountMinor={detail.refundableAmountMinor}
              submit={(payload, key) => financialApi.refund(paymentId, { amountMinor: payload.amountMinor }, key)}
              onSuccess={() => { setRefunding(false); setRefunded(true); setRefundPage(0); }} />
          </CardContent></Card>}
          <RefundHistory refunds={detail.refunds} onPageChange={setRefundPage} />
        </Stack></Grid>
        <Grid size={{ xs: 12, md: 5 }} sx={{ alignSelf: 'flex-start' }}><WalletCard /></Grid>
      </Grid>}
  </Container>;
};
