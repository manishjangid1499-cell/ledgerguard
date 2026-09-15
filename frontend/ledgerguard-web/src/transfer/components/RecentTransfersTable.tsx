import { useState } from 'react';
import { Alert, Box, Button, Card, CardContent, Chip, IconButton, Stack, Table, TableBody, TableCell, TableContainer, TableHead, TablePagination, TableRow, Tooltip, Typography } from '@mui/material';
import CallMadeIcon from '@mui/icons-material/CallMade';
import CallReceivedIcon from '@mui/icons-material/CallReceived';
import ArrowForwardIcon from '@mui/icons-material/ArrowForward';
import RefreshIcon from '@mui/icons-material/Refresh';
import { Link as RouterLink, useNavigate } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { transferApi } from '../api/transferApi';
import { formatMinorUnitsToInr } from '../../shared/utils/money';
import { formatDateTime } from '../../shared/utils/display';
import { CopyButton } from '../../shared/components/CopyButton';
import { StatusBadge } from '../../shared/components/StatusBadge';
import { DataLoading } from '../../shared/components/DataLoading';
import { getErrorMessage } from '../../shared/api/errorMessage';
import { TransferSummary } from '../types/transfer.types';

const DirectionBadge = ({ outgoing }: { outgoing: boolean }) => (
  <Chip size="small" variant="outlined" label={outgoing ? 'Sent' : 'Received'}
    icon={outgoing ? <CallMadeIcon /> : <CallReceivedIcon />} sx={{ fontWeight: 500 }} />
);

const TransferAmount = ({ transfer }: { transfer: TransferSummary }) => (
  <Typography variant="body2" sx={{ fontWeight: 700, fontVariantNumeric: 'tabular-nums', whiteSpace: 'nowrap',
    color: transfer.direction === 'OUTGOING' ? 'text.primary' : 'secondary.main' }}>
    {transfer.direction === 'OUTGOING' ? '− ' : '+ '}{formatMinorUnitsToInr(transfer.amountMinor)}
  </Typography>
);

export const RecentTransfersTable = () => {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [page, setPage] = useState(0);
  const pageSize = 10;
  const { data, isLoading, isError, error, isFetching } = useQuery({
    queryKey: ['transfers', page, pageSize],
    queryFn: () => transferApi.getTransfers(page, pageSize), staleTime: 15_000,
  });
  const refresh = () => { void queryClient.invalidateQueries({ queryKey: ['transfers'] }); };

  return (
    <Card>
      <CardContent sx={{ p: { xs: 2.5, sm: 3 } }}>
        <Stack direction="row" spacing={1} sx={{ justifyContent: 'space-between', alignItems: 'center', mb: 2.5 }}>
          <Box>
            <Typography component="h2" variant="h6" sx={{ mb: 0.5 }}>Transfer history</Typography>
            <Typography variant="body2" color="text.secondary">Transfers sent and received by your wallet.</Typography>
          </Box>
          <Tooltip title="Refresh transfer history">
            <span><IconButton aria-label="Refresh transfer history" onClick={refresh} disabled={isFetching}><RefreshIcon fontSize="small" /></IconButton></span>
          </Tooltip>
        </Stack>
        {isLoading ? <DataLoading label="Loading transfer history" /> : isError ? (
          <Alert severity="error">{getErrorMessage(error, 'Unable to load transfer history. Please try again.')}</Alert>
        ) : !data ? (
          <Alert severity="error">Transfer history is unavailable. Please try again.</Alert>
        ) : data.items.length === 0 ? (
          <Box sx={{ py: 4, px: 2, textAlign: 'center', bgcolor: 'background.default', borderRadius: 1 }}>
            <Typography variant="subtitle2" sx={{ mb: 0.75 }}>No transfers yet</Typography>
            <Typography variant="body2" color="text.secondary">Your sent and received transfers will appear here.</Typography>
          </Box>
        ) : (
          <>
            <Box sx={{ display: { xs: 'block', sm: 'none' } }}>
              {data.items.map(transfer => {
                const outgoing = transfer.direction === 'OUTGOING';
                const counterparty = outgoing ? transfer.destinationLedgerAccountId : transfer.sourceLedgerAccountId;
                return (
                  <Box key={transfer.transferId} sx={{ py: 2, borderTop: '1px solid', borderColor: 'divider' }}>
                    <Stack direction="row" spacing={1} useFlexGap sx={{ justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap' }}>
                      <DirectionBadge outgoing={outgoing} /><TransferAmount transfer={transfer} />
                    </Stack>
                    <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 1.5 }}>{formatDateTime(transfer.createdAt)}</Typography>
                    <Stack direction="row" spacing={0.5} sx={{ alignItems: 'center', my: 0.5 }}>
                      <Typography variant="caption" color="text.secondary" sx={{ fontFamily: 'monospace', overflowWrap: 'anywhere', flex: 1 }}>
                        {outgoing ? 'To' : 'From'} {counterparty}
                      </Typography>
                      <CopyButton value={counterparty} label="counterparty wallet ID" />
                    </Stack>
                    <Stack direction="row" sx={{ alignItems: 'center', justifyContent: 'space-between' }}>
                      <StatusBadge status="COMPLETED" />
                      <Button component={RouterLink} to={`/app/transfers/${transfer.transferId}`} size="small" endIcon={<ArrowForwardIcon />}
                        aria-label={`View transfer ${transfer.transferId}`}>View details</Button>
                    </Stack>
                  </Box>
                );
              })}
            </Box>
            <TableContainer tabIndex={0} role="region" aria-label="Transfer history table, scroll horizontally for more columns"
              sx={{ display: { xs: 'none', sm: 'block' }, border: '1px solid', borderColor: 'divider', borderRadius: 1 }}>
              <Table sx={{ minWidth: 780 }} aria-label="Wallet transfer history">
                <TableHead sx={{ bgcolor: 'background.default' }}>
                  <TableRow>
                    <TableCell>Direction</TableCell><TableCell align="right">Amount</TableCell>
                    <TableCell>Counterparty wallet</TableCell><TableCell>Date &amp; time</TableCell>
                    <TableCell>Status</TableCell><TableCell align="right">Details</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {data.items.map(transfer => {
                    const outgoing = transfer.direction === 'OUTGOING';
                    const counterparty = outgoing ? transfer.destinationLedgerAccountId : transfer.sourceLedgerAccountId;
                    return (
                      <TableRow key={transfer.transferId} hover onClick={() => navigate(`/app/transfers/${transfer.transferId}`)} sx={{ cursor: 'pointer' }}>
                        <TableCell><DirectionBadge outgoing={outgoing} /></TableCell>
                        <TableCell align="right"><TransferAmount transfer={transfer} /></TableCell>
                        <TableCell>
                          <Stack direction="row" sx={{ alignItems: 'center' }}>
                            <Typography variant="body2" title={counterparty} sx={{ fontFamily: 'monospace', fontSize: '0.8rem' }}>
                              {counterparty.slice(0, 8)}…{counterparty.slice(-4)}
                            </Typography>
                            <CopyButton value={counterparty} label="counterparty wallet ID" />
                          </Stack>
                        </TableCell>
                        <TableCell sx={{ color: 'text.secondary', fontSize: '0.8rem' }}>{formatDateTime(transfer.createdAt)}</TableCell>
                        <TableCell><StatusBadge status="COMPLETED" /></TableCell>
                        <TableCell align="right">
                          <IconButton component={RouterLink} to={`/app/transfers/${transfer.transferId}`}
                            aria-label={`View transfer ${transfer.transferId}`} onClick={event => event.stopPropagation()}><ArrowForwardIcon fontSize="small" /></IconButton>
                        </TableCell>
                      </TableRow>
                    );
                  })}
                </TableBody>
              </Table>
            </TableContainer>
            <TablePagination component="div" count={data.totalElements} page={data.page} rowsPerPage={pageSize}
              rowsPerPageOptions={[10]} onPageChange={(_, nextPage) => setPage(nextPage)}
              sx={{ mt: 1, '.MuiTablePagination-toolbar': { px: 0 }, '.MuiTablePagination-actions': { ml: 1 } }} />
          </>
        )}
      </CardContent>
    </Card>
  );
};
