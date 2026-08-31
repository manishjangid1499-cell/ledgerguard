import React, { useState } from 'react';
import {
  Container,
  Box,
  Typography,
  Paper,
  Stack,
  Button,
  Chip,
  IconButton,
  Tooltip,
  Skeleton,
  Alert,
  Grid,
} from '@mui/material';
import ArrowBackIcon from '@mui/icons-material/ArrowBack';
import ContentCopyIcon from '@mui/icons-material/ContentCopy';
import CheckIcon from '@mui/icons-material/Check';
import CallMadeIcon from '@mui/icons-material/CallMade';
import CallReceivedIcon from '@mui/icons-material/CallReceived';
import { useParams, useNavigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { transferApi } from '../api/transferApi';
import { JournalInspector } from '../components/JournalInspector';
import { formatMinorUnitsToInr } from '../../shared/utils/money';

export const TransferDetailPage: React.FC = () => {
  const { transferId } = useParams<{ transferId: string }>();
  const navigate = useNavigate();
  const [copiedId, setCopiedId] = useState<string | null>(null);

  const {
    data: transfer,
    isLoading,
    isError,
    error,
  } = useQuery({
    queryKey: ['transfer', transferId],
    queryFn: () => {
      if (!transferId) throw new Error('Transfer ID is missing');
      return transferApi.getTransferDetail(transferId);
    },
    enabled: !!transferId,
  });

  const handleCopy = async (text: string) => {
    try {
      await navigator.clipboard.writeText(text);
      setCopiedId(text);
      setTimeout(() => setCopiedId(null), 2000);
    } catch {
      // Fallback
    }
  };

  return (
    <Container maxWidth="lg" sx={{ py: { xs: 2, sm: 4 } }}>
      <Box sx={{ mb: 3 }}>
        <Button
          startIcon={<ArrowBackIcon />}
          onClick={() => navigate('/app')}
          variant="outlined"
          size="small"
          sx={{ mb: 2, fontWeight: 600 }}
        >
          Back to Dashboard
        </Button>
      </Box>

      {isLoading ? (
        <Stack spacing={3}>
          <Skeleton variant="rectangular" height={180} sx={{ borderRadius: 2 }} />
          <Skeleton variant="rectangular" height={260} sx={{ borderRadius: 2 }} />
        </Stack>
      ) : isError || !transfer ? (
        <Paper elevation={0} sx={{ p: 4, border: '1px solid', borderColor: 'divider', borderRadius: 2 }}>
          <Alert severity="error" sx={{ mb: 2 }}>
            {error instanceof Error
              ? error.message
              : 'Transfer not found or you do not have permission to view it.'}
          </Alert>
          <Button variant="contained" onClick={() => navigate('/app')}>
            Return to Dashboard
          </Button>
        </Paper>
      ) : (
        <Stack spacing={3}>
          {/* Transfer Summary Header */}
          <Paper
            elevation={0}
            sx={{
              p: { xs: 2.5, sm: 3.5 },
              border: '1px solid',
              borderColor: 'divider',
              borderRadius: 2,
              bgcolor: 'background.paper',
            }}
          >
            <Stack
              direction={{ xs: 'column', sm: 'row' }}
              spacing={2}
              sx={{ justifyContent: 'space-between', alignItems: { sm: 'center' }, mb: 2.5 }}
            >
              <Box>
                <Stack direction="row" spacing={1} sx={{ alignItems: 'center', mb: 0.5 }}>
                  <Chip
                    icon={
                      transfer.direction === 'OUTGOING' ? (
                        <CallMadeIcon fontSize="small" />
                      ) : (
                        <CallReceivedIcon fontSize="small" />
                      )
                    }
                    label={transfer.direction === 'OUTGOING' ? 'Outgoing Transfer' : 'Incoming Transfer'}
                    color={transfer.direction === 'OUTGOING' ? 'default' : 'success'}
                    variant="filled"
                    sx={{ fontWeight: 700 }}
                  />
                  <Chip label="Completed" color="success" size="small" variant="outlined" sx={{ fontWeight: 600 }} />
                </Stack>
                <Typography variant="caption" color="text.secondary" sx={{ display: 'block' }}>
                  Transfer ID: <code style={{ fontWeight: 600 }}>{transfer.transferId}</code>
                </Typography>
              </Box>

              <Box sx={{ textAlign: { sm: 'right' } }}>
                <Typography variant="caption" color="text.secondary" sx={{ display: 'block' }}>
                  Transfer Amount
                </Typography>
                <Typography
                  variant="h4"
                  component="div"
                  sx={{
                    fontWeight: 800,
                    color: transfer.direction === 'OUTGOING' ? 'text.primary' : 'success.main',
                    letterSpacing: -0.5,
                  }}
                >
                  {transfer.direction === 'OUTGOING' ? '- ' : '+ '}
                  {formatMinorUnitsToInr(transfer.amountMinor)}
                </Typography>
              </Box>
            </Stack>

            <Grid container spacing={2}>
              <Grid size={{ xs: 12, md: 6 }}>
                <Box sx={{ p: 2, bgcolor: 'background.default', borderRadius: 1.5, border: '1px solid', borderColor: 'divider' }}>
                  <Typography variant="caption" color="text.secondary" sx={{ display: 'block' }}>
                    Source Wallet (Debited)
                  </Typography>
                  <Stack direction="row" spacing={0.5} sx={{ alignItems: 'center' }}>
                    <Typography variant="body2" sx={{ fontFamily: 'monospace', fontWeight: 600, wordBreak: 'break-all' }}>
                      {transfer.sourceLedgerAccountId}
                    </Typography>
                    <Tooltip title={copiedId === transfer.sourceLedgerAccountId ? 'Copied!' : 'Copy'}>
                      <IconButton size="small" onClick={() => handleCopy(transfer.sourceLedgerAccountId)}>
                        {copiedId === transfer.sourceLedgerAccountId ? <CheckIcon fontSize="small" color="success" /> : <ContentCopyIcon fontSize="small" />}
                      </IconButton>
                    </Tooltip>
                  </Stack>
                </Box>
              </Grid>

              <Grid size={{ xs: 12, md: 6 }}>
                <Box sx={{ p: 2, bgcolor: 'background.default', borderRadius: 1.5, border: '1px solid', borderColor: 'divider' }}>
                  <Typography variant="caption" color="text.secondary" sx={{ display: 'block' }}>
                    Destination Wallet (Credited)
                  </Typography>
                  <Stack direction="row" spacing={0.5} sx={{ alignItems: 'center' }}>
                    <Typography variant="body2" sx={{ fontFamily: 'monospace', fontWeight: 600, wordBreak: 'break-all' }}>
                      {transfer.destinationLedgerAccountId}
                    </Typography>
                    <Tooltip title={copiedId === transfer.destinationLedgerAccountId ? 'Copied!' : 'Copy'}>
                      <IconButton size="small" onClick={() => handleCopy(transfer.destinationLedgerAccountId)}>
                        {copiedId === transfer.destinationLedgerAccountId ? <CheckIcon fontSize="small" color="success" /> : <ContentCopyIcon fontSize="small" />}
                      </IconButton>
                    </Tooltip>
                  </Stack>
                </Box>
              </Grid>
            </Grid>

            <Box sx={{ mt: 2, pt: 2, borderTop: '1px solid', borderColor: 'divider' }}>
              <Typography variant="caption" color="text.secondary">
                Initiated At: {new Date(transfer.createdAt).toLocaleString('en-IN', { dateStyle: 'full', timeStyle: 'medium' })}
              </Typography>
            </Box>
          </Paper>

          {/* Double Entry Journal Inspector */}
          {transfer.journal && (
            <JournalInspector
              journal={transfer.journal}
              sourceLedgerAccountId={transfer.sourceLedgerAccountId}
              destinationLedgerAccountId={transfer.destinationLedgerAccountId}
            />
          )}
        </Stack>
      )}
    </Container>
  );
};
