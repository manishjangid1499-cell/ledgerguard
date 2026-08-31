import React, { useState } from 'react';
import {
  Card,
  CardContent,
  Typography,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  TablePagination,
  Chip,
  IconButton,
  Tooltip,
  Box,
  Skeleton,
  Stack,
  Alert,
} from '@mui/material';
import CallMadeIcon from '@mui/icons-material/CallMade';
import CallReceivedIcon from '@mui/icons-material/CallReceived';
import VisibilityIcon from '@mui/icons-material/Visibility';
import ContentCopyIcon from '@mui/icons-material/ContentCopy';
import CheckIcon from '@mui/icons-material/Check';
import RefreshIcon from '@mui/icons-material/Refresh';
import { useNavigate } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { transferApi } from '../api/transferApi';
import { formatMinorUnitsToInr } from '../../shared/utils/money';
import { TransferSummary } from '../types/transfer.types';

function formatDate(isoString: string): string {
  try {
    const d = new Date(isoString);
    return d.toLocaleString('en-IN', {
      dateStyle: 'medium',
      timeStyle: 'short',
    });
  } catch {
    return isoString;
  }
}

export const RecentTransfersTable: React.FC = () => {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [page, setPage] = useState(0);
  const [copiedId, setCopiedId] = useState<string | null>(null);
  const pageSize = 10;

  const {
    data,
    isLoading,
    isError,
    error,
    isFetching,
  } = useQuery({
    queryKey: ['transfers', page, pageSize],
    queryFn: () => transferApi.getTransfers(page, pageSize),
    staleTime: 15_000,
  });

  const handleCopy = async (id: string, e: React.MouseEvent) => {
    e.stopPropagation();
    try {
      await navigator.clipboard.writeText(id);
      setCopiedId(id);
      setTimeout(() => setCopiedId(null), 2000);
    } catch {
      // Fallback
    }
  };

  const handleRefresh = () => {
    queryClient.invalidateQueries({ queryKey: ['transfers'] });
  };

  const handleRowClick = (transferId: string) => {
    navigate(`/app/transfers/${transferId}`);
  };

  return (
    <Card elevation={0} sx={{ border: '1px solid', borderColor: 'divider', borderRadius: 2 }}>
      <CardContent sx={{ p: { xs: 2, sm: 3 } }}>
        <Stack
          direction="row"
          sx={{ justifyContent: 'space-between', alignItems: 'center', mb: 2 }}
        >
          <Box>
            <Typography variant="h6" sx={{ fontWeight: 700 }}>
              Transfer History
            </Typography>
            <Typography variant="body2" color="text.secondary">
              Immutable double-entry transfer ledger records for your wallet.
            </Typography>
          </Box>

          <Tooltip title="Refresh transfer history">
            <span>
              <IconButton size="small" onClick={handleRefresh} disabled={isFetching}>
                <RefreshIcon
                  fontSize="small"
                  sx={{ animation: isFetching ? 'spin 1s linear infinite' : 'none' }}
                />
              </IconButton>
            </span>
          </Tooltip>
        </Stack>

        {isLoading ? (
          <Stack spacing={1}>
            <Skeleton variant="rectangular" height={40} sx={{ borderRadius: 1 }} />
            <Skeleton variant="rectangular" height={48} sx={{ borderRadius: 1 }} />
            <Skeleton variant="rectangular" height={48} sx={{ borderRadius: 1 }} />
          </Stack>
        ) : isError ? (
          <Alert severity="error">
            Failed to load transfer history: {error instanceof Error ? error.message : 'Unknown error'}
          </Alert>
        ) : !data || data.items.length === 0 ? (
          <Box
            sx={{
              py: 6,
              textAlign: 'center',
              bgcolor: 'background.default',
              borderRadius: 2,
              border: '1px dashed',
              borderColor: 'divider',
            }}
          >
            <Typography variant="subtitle1" sx={{ fontWeight: 600, color: 'text.secondary', mb: 0.5 }}>
              No transfers yet
            </Typography>
            <Typography variant="body2" color="text.secondary">
              Transfers sent or received by your wallet will appear here with full ledger auditability.
            </Typography>
          </Box>
        ) : (
          <>
            <TableContainer sx={{ borderRadius: 1.5, border: '1px solid', borderColor: 'divider' }}>
              <Table size="medium">
                <TableHead sx={{ bgcolor: 'action.hover' }}>
                  <TableRow>
                    <TableCell sx={{ fontWeight: 700 }}>Type</TableCell>
                    <TableCell sx={{ fontWeight: 700 }}>Amount</TableCell>
                    <TableCell sx={{ fontWeight: 700 }}>Counterparty</TableCell>
                    <TableCell sx={{ fontWeight: 700 }}>Date & Time</TableCell>
                    <TableCell sx={{ fontWeight: 700 }}>Status</TableCell>
                    <TableCell align="right" sx={{ fontWeight: 700 }}>Details</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {data.items.map((transfer: TransferSummary) => {
                    const isOutgoing = transfer.direction === 'OUTGOING';
                    const counterpartyId = isOutgoing
                      ? transfer.destinationLedgerAccountId
                      : transfer.sourceLedgerAccountId;
                    const formattedAmount = formatMinorUnitsToInr(transfer.amountMinor);

                    return (
                      <TableRow
                        key={transfer.transferId}
                        hover
                        onClick={() => handleRowClick(transfer.transferId)}
                        sx={{ cursor: 'pointer' }}
                      >
                        <TableCell>
                          <Chip
                            icon={
                              isOutgoing ? (
                                <CallMadeIcon fontSize="small" />
                              ) : (
                                <CallReceivedIcon fontSize="small" />
                              )
                            }
                            label={isOutgoing ? 'Outgoing' : 'Incoming'}
                            color={isOutgoing ? 'default' : 'success'}
                            size="small"
                            variant="outlined"
                            sx={{ fontWeight: 600 }}
                          />
                        </TableCell>

                        <TableCell sx={{ fontWeight: 700, fontFamily: 'monospace', fontSize: '0.95rem' }}>
                          <Typography
                            variant="body2"
                            sx={{
                              fontWeight: 700,
                              color: isOutgoing ? 'text.primary' : 'success.main',
                            }}
                          >
                            {isOutgoing ? `- ${formattedAmount}` : `+ ${formattedAmount}`}
                          </Typography>
                        </TableCell>

                        <TableCell>
                          <Stack direction="row" spacing={0.5} sx={{ alignItems: 'center' }}>
                            <Typography
                              variant="body2"
                              sx={{
                                fontFamily: 'monospace',
                                fontSize: '0.85rem',
                                color: 'text.secondary',
                              }}
                            >
                              {counterpartyId.substring(0, 8)}...{counterpartyId.substring(counterpartyId.length - 4)}
                            </Typography>
                            <Tooltip title={copiedId === counterpartyId ? 'Copied!' : 'Copy full ID'}>
                              <IconButton
                                size="small"
                                onClick={(e) => handleCopy(counterpartyId, e)}
                                sx={{ p: 0.5 }}
                              >
                                {copiedId === counterpartyId ? (
                                  <CheckIcon fontSize="inherit" color="success" />
                                ) : (
                                  <ContentCopyIcon fontSize="inherit" />
                                )}
                              </IconButton>
                            </Tooltip>
                          </Stack>
                        </TableCell>

                        <TableCell sx={{ color: 'text.secondary', fontSize: '0.85rem' }}>
                          {formatDate(transfer.createdAt)}
                        </TableCell>

                        <TableCell>
                          <Chip
                            label="Completed"
                            color="success"
                            size="small"
                            sx={{ fontWeight: 600, fontSize: '0.75rem' }}
                          />
                        </TableCell>

                        <TableCell align="right">
                          <IconButton
                            size="small"
                            onClick={(e) => {
                              e.stopPropagation();
                              handleRowClick(transfer.transferId);
                            }}
                          >
                            <VisibilityIcon fontSize="small" />
                          </IconButton>
                        </TableCell>
                      </TableRow>
                    );
                  })}
                </TableBody>
              </Table>
            </TableContainer>

            <TablePagination
              component="div"
              count={data.totalElements}
              page={data.page}
              rowsPerPage={pageSize}
              rowsPerPageOptions={[10]}
              onPageChange={(_, newPage) => setPage(newPage)}
              sx={{ borderTop: 'none', mt: 1 }}
            />
          </>
        )}
      </CardContent>
    </Card>
  );
};
