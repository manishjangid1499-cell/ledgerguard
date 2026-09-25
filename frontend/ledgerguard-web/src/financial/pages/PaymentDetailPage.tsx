import { useEffect, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
  CircularProgress,
  Container,
  Dialog,
  DialogActions,
  DialogContent,
  DialogContentText,
  DialogTitle,
  Divider,
  Grid,
  InputAdornment,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import ArrowBackIcon from '@mui/icons-material/ArrowBack';
import RefreshIcon from '@mui/icons-material/Refresh';
import { Link as RouterLink, useParams } from 'react-router-dom';
import { financialApi } from '../api';
import { financialError } from '../feedback';
import { RecordFields } from '../components/RecordFields';
import { RefundHistory } from '../components/RefundHistory';
import { useAuth } from '../../auth/hooks/useAuth';
import { DataLoading } from '../../shared/components/DataLoading';
import { StatusBadge } from '../../shared/components/StatusBadge';
import { AmountDisplay } from '../../shared/components/AmountDisplay';
import { WalletCard } from '../../wallet/components/WalletCard';
import { formatMinorUnitsToInr, parseInrToMinorUnits } from '../../shared/utils/money';
import { formatDateTime } from '../../shared/utils/display';
import { getErrorMessage } from '../../shared/api/errorMessage';

export const PaymentDetailPage = () => {
  const { paymentId = '' } = useParams();
  const { user } = useAuth();
  const queryClient = useQueryClient();
  const [refundPage, setRefundPage] = useState(0);
  const [showRefundForm, setShowRefundForm] = useState(false);
  const [refundAmountStr, setRefundAmountStr] = useState('');
  const [refundValidationError, setRefundValidationError] = useState<string | null>(null);
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [isSubmittingRefund, setIsSubmittingRefund] = useState(false);
  const [refundSubmitError, setRefundSubmitError] = useState<string | null>(null);
  const [refundSuccessMessage, setRefundSuccessMessage] = useState<string | null>(null);
  const [refundIdempotencyKey, setRefundIdempotencyKey] = useState<string>(() => crypto.randomUUID());

  const query = useQuery({
    queryKey: ['payments', 'detail', paymentId, refundPage],
    queryFn: () => financialApi.paymentDetail(paymentId, refundPage),
    enabled: Boolean(paymentId),
    staleTime: 0,
  });

  const detail = query.data;
  const payment = detail?.payment;
  const refundedAmount = detail?.refundedAmountMinor;

  useEffect(() => {
    if (refundedAmount !== undefined) void queryClient.invalidateQueries({ queryKey: ['wallet'] });
  }, [paymentId, refundedAmount, queryClient]);

  const maxRefundableMinor = detail && /^\d+$/.test(detail.refundableAmountMinor)
    ? BigInt(detail.refundableAmountMinor)
    : 0n;

  const eligible = user?.role === 'MERCHANT' && payment?.status === 'SUCCEEDED' && maxRefundableMinor > 0n;

  // Validation of refund input
  const parsedRefund = parseInrToMinorUnits(refundAmountStr);
  const requestedRefundMinor = parsedRefund.ok && parsedRefund.minorUnits !== undefined
    ? BigInt(parsedRefund.minorUnits)
    : 0n;
  const isRefundValid = parsedRefund.ok && requestedRefundMinor > 0n;
  const exceedsRefundable = isRefundValid && requestedRefundMinor > maxRefundableMinor;
  const remainingAfterRefund = exceedsRefundable ? 0n : maxRefundableMinor - requestedRefundMinor;

  const handleUseFullRemaining = () => {
    if (maxRefundableMinor <= 0n) return;
    const rupees = maxRefundableMinor / 100n;
    const paise = maxRefundableMinor % 100n;
    setRefundAmountStr(`${rupees}.${paise.toString().padStart(2, '0')}`);
    setRefundValidationError(null);
  };

  const handleOpenConfirm = (e: React.FormEvent) => {
    e.preventDefault();
    setRefundValidationError(null);
    setRefundSubmitError(null);

    if (!refundAmountStr.trim()) {
      setRefundValidationError('Please enter a refund amount.');
      return;
    }
    if (!parsedRefund.ok || parsedRefund.minorUnits === undefined || parsedRefund.minorUnits <= 0) {
      setRefundValidationError(parsedRefund.error || 'Enter a positive amount with up to 2 decimal places.');
      return;
    }
    if (requestedRefundMinor > maxRefundableMinor) {
      setRefundValidationError(`Refund amount exceeds remaining refundable amount (${formatMinorUnitsToInr(String(maxRefundableMinor))}).`);
      return;
    }

    setConfirmOpen(true);
  };

  const handleExecuteRefund = async () => {
    if (!parsedRefund.ok || parsedRefund.minorUnits === undefined) return;
    setIsSubmittingRefund(true);
    setRefundSubmitError(null);

    try {
      const result = await financialApi.refund(
        paymentId,
        { amountMinor: parsedRefund.minorUnits },
        refundIdempotencyKey
      );

      setConfirmOpen(false);
      setShowRefundForm(false);
      setRefundAmountStr('');
      setRefundIdempotencyKey(crypto.randomUUID());
      setRefundSuccessMessage(`Refund of ${formatMinorUnitsToInr(result.refundAmountMinor)} posted successfully (Refund ID: ${result.refundId}).`);

      // Targeted invalidation
      void queryClient.invalidateQueries({ queryKey: ['payments', 'detail', paymentId] });
      void queryClient.invalidateQueries({ queryKey: ['payments', 'recent'] });
      void queryClient.invalidateQueries({ queryKey: ['payments', 'list'] });
      void queryClient.invalidateQueries({ queryKey: ['merchantSummary'] });
      void queryClient.invalidateQueries({ queryKey: ['wallet'] });
      setRefundPage(0);
    } catch (err: unknown) {
      setConfirmOpen(false);
      setRefundSubmitError(getErrorMessage(err, 'The refund request could not be completed. Review your records and try again.'));
    } finally {
      setIsSubmittingRefund(false);
    }
  };

  return (
    <Container maxWidth="lg">
      <Button component={RouterLink} to="/app/activity?type=payments" startIcon={<ArrowBackIcon />} sx={{ mb: 2 }}>
        Back to customer payments
      </Button>
      <Stack direction="row" useFlexGap spacing={2} sx={{ justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', mb: 3 }}>
        <Typography component="h1" variant="h4" color="primary.main" sx={{ fontSize: { xs: '1.75rem', md: '2rem' }, fontWeight: 700 }}>
          Payment details
        </Typography>
        <Button variant="outlined" startIcon={<RefreshIcon />} disabled={query.isFetching} onClick={() => { void query.refetch(); }}>
          Refresh payment
        </Button>
      </Stack>

      {query.isError && <Alert severity="error" sx={{ mb: 3 }}>{financialError(query.error)}</Alert>}
      {refundSuccessMessage && (
        <Alert severity="success" onClose={() => setRefundSuccessMessage(null)} sx={{ mb: 3 }}>
          {refundSuccessMessage}
        </Alert>
      )}

      {query.isLoading ? (
        <DataLoading label="Loading payment details" minHeight={360} />
      ) : detail && payment && (
        <Grid container spacing={3}>
          <Grid size={{ xs: 12, md: 7 }}>
            <Stack spacing={3}>
              <Card>
                <CardContent sx={{ p: { xs: 2.5, sm: 3 } }}>
                  <Typography variant="body2" color="text.secondary">Gross payment · {payment.currency}</Typography>
                  <AmountDisplay amount={payment.grossAmountMinor} />
                  <StatusBadge status={payment.status} />

                  {/* Merchant-friendly financial fields */}
                  <Box sx={{ mt: 2.5 }}>
                    <Typography component="h2" variant="subtitle2" color="text.secondary" sx={{ textTransform: 'uppercase', letterSpacing: 0.5, fontSize: '0.75rem', mb: 1, fontWeight: 700 }}>
                      Financial breakdown
                    </Typography>
                    <RecordFields fields={[
                      { label: 'Platform fee', value: formatMinorUnitsToInr(payment.feeAmountMinor) },
                      { label: 'Merchant net amount', value: formatMinorUnitsToInr(payment.merchantNetAmountMinor) },
                      { label: 'Total refunded', value: formatMinorUnitsToInr(detail.refundedAmountMinor) },
                      { label: 'Remaining refundable amount', value: formatMinorUnitsToInr(detail.refundableAmountMinor) },
                      { label: 'Created date', value: formatDateTime(payment.createdAt) },
                      ...(payment.completedAt ? [{ label: 'Completed date', value: formatDateTime(payment.completedAt) }] : []),
                    ]} />
                  </Box>

                  <Divider sx={{ my: 3 }} />

                  {/* Technical Details */}
                  <Box>
                    <Typography component="h3" variant="subtitle2" color="text.secondary" sx={{ textTransform: 'uppercase', letterSpacing: 0.5, fontSize: '0.75rem', mb: 1, fontWeight: 700 }}>
                      Technical details
                    </Typography>
                    <RecordFields fields={[
                      { label: 'Payment ID', value: payment.paymentId, copy: true },
                      { label: 'Customer wallet ID', value: payment.customerLedgerAccountId, copy: true },
                      { label: 'Merchant wallet ID', value: payment.merchantLedgerAccountId, copy: true },
                      ...(payment.journalTransactionId ? [{ label: 'Journal transaction ID', value: payment.journalTransactionId, copy: true }] : []),
                    ]} />
                  </Box>

                  {eligible && !showRefundForm && (
                    <Button
                      variant="outlined"
                      color="primary"
                      sx={{ mt: 3, fontWeight: 600 }}
                      onClick={() => {
                        setShowRefundForm(true);
                        setRefundSuccessMessage(null);
                        setRefundSubmitError(null);
                      }}
                    >
                      Issue refund
                    </Button>
                  )}
                </CardContent>
              </Card>

              {/* Refund Form Card */}
              {showRefundForm && (
                <Card sx={{ borderLeft: '4px solid', borderColor: 'warning.main' }}>
                  <CardContent sx={{ p: { xs: 2.5, sm: 3 } }}>
                    <Typography component="h2" variant="h6" sx={{ mb: 1, fontWeight: 600 }}>
                      Issue a refund
                    </Typography>
                    <Typography variant="body2" color="text.secondary" sx={{ mb: 2.5 }}>
                      Enter a partial amount or refund the full remaining balance. Platform fee allocation is automatically reversed in accordance with policy.
                    </Typography>

                    {refundSubmitError && (
                      <Alert severity="error" sx={{ mb: 2 }} onClose={() => setRefundSubmitError(null)}>
                        {refundSubmitError}
                      </Alert>
                    )}

                    <Box component="form" noValidate onSubmit={handleOpenConfirm}>
                      <Stack spacing={2.5}>
                        <TextField
                          id="refund-amount"
                          label="Refund amount (INR)"
                          required
                          fullWidth
                          value={refundAmountStr}
                          disabled={isSubmittingRefund}
                          error={Boolean(refundValidationError || exceedsRefundable)}
                          helperText={
                            refundValidationError ||
                            (exceedsRefundable
                              ? `Cannot exceed remaining refundable amount of ${formatMinorUnitsToInr(detail.refundableAmountMinor)}`
                              : 'Enter a positive amount with up to 2 decimal places.')
                          }
                          onChange={e => {
                            setRefundAmountStr(e.target.value);
                            setRefundValidationError(null);
                            setRefundSubmitError(null);
                          }}
                          slotProps={{
                            htmlInput: { inputMode: 'decimal' },
                            input: {
                              startAdornment: <InputAdornment position="start">₹</InputAdornment>,
                            },
                          }}
                        />

                        <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', gap: 1 }}>
                          <Typography variant="body2" color="text.secondary">
                            Remaining refundable: <strong>{formatMinorUnitsToInr(detail.refundableAmountMinor)}</strong>
                          </Typography>
                          <Button
                            size="small"
                            onClick={handleUseFullRemaining}
                            disabled={isSubmittingRefund || maxRefundableMinor <= 0n}
                          >
                            Use full remaining amount
                          </Button>
                        </Box>

                        <Stack direction="row" spacing={1.5} sx={{ pt: 1 }}>
                          <Button
                            type="submit"
                            variant="contained"
                            color="primary"
                            disabled={isSubmittingRefund || !refundAmountStr || exceedsRefundable}
                          >
                            Review refund
                          </Button>
                          <Button
                            variant="text"
                            color="inherit"
                            disabled={isSubmittingRefund}
                            onClick={() => {
                              setShowRefundForm(false);
                              setRefundAmountStr('');
                              setRefundValidationError(null);
                              setRefundSubmitError(null);
                            }}
                          >
                            Cancel
                          </Button>
                        </Stack>
                      </Stack>
                    </Box>
                  </CardContent>
                </Card>
              )}

              {/* Refund History Table */}
              <RefundHistory refunds={detail.refunds} onPageChange={setRefundPage} />
            </Stack>
          </Grid>
          <Grid size={{ xs: 12, md: 5 }} sx={{ alignSelf: 'flex-start' }}>
            <WalletCard />
          </Grid>
        </Grid>
      )}

      {/* Refund Confirmation Dialog */}
      <Dialog
        open={confirmOpen}
        onClose={() => { if (!isSubmittingRefund) setConfirmOpen(false); }}
        aria-labelledby="confirm-refund-dialog-title"
        aria-describedby="confirm-refund-dialog-description"
      >
        <DialogTitle id="confirm-refund-dialog-title" sx={{ fontWeight: 700 }}>
          Confirm refund of {isRefundValid ? formatMinorUnitsToInr(String(requestedRefundMinor)) : ''}?
        </DialogTitle>
        <DialogContent>
          <DialogContentText id="confirm-refund-dialog-description" sx={{ color: 'text.secondary', mb: 2 }}>
            This financial operation will debit your merchant wallet and reverse the proportional platform fee. This cannot simply be undone once posted.
          </DialogContentText>
          <Box sx={{ p: 2, bgcolor: 'background.default', borderRadius: 1 }}>
            <Stack spacing={1}>
              <Stack direction="row" sx={{ justifyContent: 'space-between' }}>
                <Typography variant="body2" color="text.secondary">Current remaining refundable:</Typography>
                <Typography variant="body2" sx={{ fontWeight: 600 }}>{formatMinorUnitsToInr(String(maxRefundableMinor))}</Typography>
              </Stack>
              <Stack direction="row" sx={{ justifyContent: 'space-between' }}>
                <Typography variant="body2" color="text.secondary">Refund amount to issue:</Typography>
                <Typography variant="body2" sx={{ fontWeight: 600, color: 'primary.main' }}>
                  {formatMinorUnitsToInr(String(requestedRefundMinor))}
                </Typography>
              </Stack>
              <Stack direction="row" sx={{ justifyContent: 'space-between', pt: 1, borderTop: '1px solid', borderColor: 'divider' }}>
                <Typography variant="body2" color="text.secondary">Expected remaining refundable afterward:</Typography>
                <Typography variant="body2" sx={{ fontWeight: 700 }}>{formatMinorUnitsToInr(String(remainingAfterRefund))}</Typography>
              </Stack>
            </Stack>
          </Box>
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2.5 }}>
          <Button onClick={() => setConfirmOpen(false)} disabled={isSubmittingRefund} color="inherit">
            Cancel
          </Button>
          <Button
            onClick={handleExecuteRefund}
            variant="contained"
            color="primary"
            disabled={isSubmittingRefund}
            startIcon={isSubmittingRefund ? <CircularProgress size={16} color="inherit" /> : undefined}
          >
            {isSubmittingRefund ? 'Posting refund…' : 'Confirm refund'}
          </Button>
        </DialogActions>
      </Dialog>
    </Container>
  );
};
