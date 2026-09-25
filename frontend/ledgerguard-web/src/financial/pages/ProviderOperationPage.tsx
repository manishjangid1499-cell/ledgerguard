import { Alert, Button, Card, CardContent, Container, Grid, Stack, Typography } from '@mui/material';
import ArrowBackIcon from '@mui/icons-material/ArrowBack';
import RefreshIcon from '@mui/icons-material/Refresh';
import { Link as RouterLink, useParams } from 'react-router-dom';
import { useProviderOperation } from '../hooks';
import { financialError, needsConfirmation, providerMessage } from '../feedback';
import { ProviderDomain } from '../types';
import { RecordFields } from '../components/RecordFields';
import { DataLoading } from '../../shared/components/DataLoading';
import { AmountDisplay } from '../../shared/components/AmountDisplay';
import { StatusBadge } from '../../shared/components/StatusBadge';
import { WalletCard } from '../../wallet/components/WalletCard';
import { formatDateTime } from '../../shared/utils/display';

export const ProviderOperationPage = ({ domain }: { domain: ProviderDomain }) => {
  const { operationId = '' } = useParams();
  const query = useProviderOperation(domain, operationId);
  const operation = query.data;
  const title = domain === 'funding' ? 'Funding details' : 'Payout details';
  return <Container maxWidth="lg">
    <Button component={RouterLink} to={`/app/${domain}`} startIcon={<ArrowBackIcon />} sx={{ mb: 2 }}>Back to {domain === 'funding' ? 'funding' : 'payouts'}</Button>
    <Stack direction="row" useFlexGap spacing={2} sx={{ justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', mb: 3 }}>
      <Typography component="h1" variant="h4" color="primary.main" sx={{ fontSize: { xs: '1.75rem', md: '2rem' } }}>{title}</Typography>
      <Button variant="outlined" startIcon={<RefreshIcon />} disabled={query.isFetching} onClick={() => { void query.refetch(); }}>Refresh status</Button>
    </Stack>
    {query.isError && <Alert severity="error" sx={{ mb: 3 }}>{financialError(query.error)} Status checks are paused; use Refresh status to try again.</Alert>}
    {query.isLoading ? <DataLoading label={`Loading ${title.toLowerCase()}`} minHeight={360} /> : operation &&
      <Grid container spacing={3}>
        <Grid size={{ xs: 12, md: 7 }}>
          <Card><CardContent sx={{ p: { xs: 2.5, sm: 3 } }}>
            <Typography variant="body2" color="text.secondary">{domain === 'funding' ? 'Funding amount' : 'Payout amount'} · {operation.currency}</Typography>
            <AmountDisplay amount={operation.amountMinor} />
            <StatusBadge status={operation.status} />
            <Alert severity={operation.status === 'FAILED' ? 'error' : operation.status === 'SUCCEEDED' ? 'success' : 'info'} sx={{ mt: 3 }}>
              {providerMessage(domain, operation.status)}
            </Alert>
            {domain === 'payouts' && (
              <Alert severity="info" variant="outlined" sx={{ mt: 2 }}>
                <strong>Simulation rail:</strong> LedgerGuard simulates payment rail settlement. Payouts hold funds in your wallet immediately upon creation. Confirmed settlement consumes the hold; failed payouts release the hold back to your available balance.
              </Alert>
            )}
            {!query.isError && needsConfirmation(operation.status) && <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 1 }}>Status checks every 4 seconds while this page is active.</Typography>}
            <RecordFields fields={[
              { label: domain === 'funding' ? 'Funding ID' : 'Payout ID', value: operationId, copy: true },
              ...(domain === 'payouts' ? [{
                label: 'Hold status',
                value: operation.status === 'SUCCEEDED'
                  ? 'Consumed (Settled)'
                  : operation.status === 'FAILED'
                  ? 'Released (Available balance restored)'
                  : 'Active (Funds held in wallet)'
              }] : []),
              { label: 'Requested', value: formatDateTime(operation.createdAt) },
              ...(operation.completedAt ? [{ label: 'Completed', value: formatDateTime(operation.completedAt) }] : []),
              { label: 'Provider reference', value: operation.providerOperationId, copy: true },
              ...(operation.journalTransactionId ? [{ label: 'Journal transaction ID', value: operation.journalTransactionId, copy: true }] : []),
            ]} />
          </CardContent></Card>
        </Grid>
        <Grid size={{ xs: 12, md: 5 }}><WalletCard /></Grid>
      </Grid>}
  </Container>;
};
