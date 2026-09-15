import { Alert, Box, Button, Chip, Container, Grid, Paper, Stack, Typography } from '@mui/material';
import ArrowBackIcon from '@mui/icons-material/ArrowBack';
import CallMadeIcon from '@mui/icons-material/CallMade';
import CallReceivedIcon from '@mui/icons-material/CallReceived';
import { Link as RouterLink, useParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { transferApi } from '../api/transferApi';
import { JournalInspector } from '../components/JournalInspector';
import { AmountDisplay } from '../../shared/components/AmountDisplay';
import { formatDateTime } from '../../shared/utils/display';
import { CopyButton } from '../../shared/components/CopyButton';
import { DataLoading } from '../../shared/components/DataLoading';
import { StatusBadge } from '../../shared/components/StatusBadge';
import { getErrorMessage } from '../../shared/api/errorMessage';

export const TransferDetailPage = () => {
  const { transferId } = useParams<{ transferId: string }>();
  const { data: transfer, isLoading, isError, error } = useQuery({
    queryKey: ['transfer', transferId],
    queryFn: () => {
      if (!transferId) throw new Error('Transfer ID is missing');
      return transferApi.getTransferDetail(transferId);
    },
    enabled: !!transferId,
  });
  return (
    <Container maxWidth="lg">
      <Button component={RouterLink} to="/app" startIcon={<ArrowBackIcon />} size="small" sx={{ mb: 2 }}>Back to dashboard</Button>
      <Typography component="h1" variant="h4" color="primary.main" sx={{ mb: 3, fontSize: { xs: '1.75rem', md: '2rem' } }}>Transfer details</Typography>
      {isLoading ? <DataLoading label="Loading transfer details" minHeight={440} /> : isError || !transfer ? (
        <Alert severity="error">{getErrorMessage(error, 'This transfer could not be loaded. It may be unavailable or outside your account.')}</Alert>
      ) : (
        <Stack spacing={3}>
          <Paper variant="outlined" sx={{ p: { xs: 2.5, sm: 3 } }}>
            <Stack direction={{ xs: 'column', md: 'row' }} spacing={3} sx={{ justifyContent: 'space-between', mb: 3 }}>
              <Box sx={{ minWidth: 0 }}>
                <Stack direction="row" spacing={1} useFlexGap sx={{ alignItems: 'center', flexWrap: 'wrap', mb: 1.5 }}>
                  <Chip icon={transfer.direction === 'OUTGOING' ? <CallMadeIcon /> : <CallReceivedIcon />}
                    label={transfer.direction === 'OUTGOING' ? 'Sent' : 'Received'} variant="outlined" size="small" />
                  {transfer.journal && <StatusBadge status={transfer.journal.status} />}
                </Stack>
                <Typography variant="caption" color="text.secondary">Transfer ID</Typography>
                <Typography variant="body2" sx={{ fontFamily: 'monospace', overflowWrap: 'anywhere' }}>{transfer.transferId}</Typography>
              </Box>
              <Box sx={{ textAlign: { md: 'right' }, minWidth: 0 }}>
                <Typography variant="caption" color="text.secondary">Amount · {transfer.currency}</Typography>
                <AmountDisplay amount={transfer.amountMinor} prefix={transfer.direction === 'OUTGOING' ? '− ' : '+ '}
                  color={transfer.direction === 'OUTGOING' ? 'primary.main' : 'secondary.main'} />
              </Box>
            </Stack>
            <Grid container spacing={2}>
              {[
                ['Source wallet · Debited', transfer.sourceLedgerAccountId, 'source wallet ID'],
                ['Recipient wallet · Credited', transfer.destinationLedgerAccountId, 'recipient wallet ID'],
              ].map(([label, value, copyLabel]) => (
                <Grid key={label} size={{ xs: 12, md: 6 }}>
                  <Box sx={{ p: 2, bgcolor: 'background.default', borderRadius: 1 }}>
                    <Typography variant="caption" color="text.secondary">{label}</Typography>
                    <Stack direction="row" spacing={0.5} sx={{ alignItems: 'center' }}>
                      <Typography variant="body2" sx={{ fontFamily: 'monospace', overflowWrap: 'anywhere', flex: 1, minWidth: 0 }}>{value}</Typography>
                      <CopyButton value={value} label={copyLabel} />
                    </Stack>
                  </Box>
                </Grid>
              ))}
            </Grid>
            <Typography variant="body2" color="text.secondary" sx={{ mt: 2.5 }}>Created {formatDateTime(transfer.createdAt)}</Typography>
          </Paper>
          {transfer.journal && <JournalInspector journal={transfer.journal} sourceLedgerAccountId={transfer.sourceLedgerAccountId}
            destinationLedgerAccountId={transfer.destinationLedgerAccountId} />}
        </Stack>
      )}
    </Container>
  );
};
