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
  Chip,
  IconButton,
  Tooltip,
  Box,
  Stack,
  Divider,
} from '@mui/material';
import ContentCopyIcon from '@mui/icons-material/ContentCopy';
import CheckIcon from '@mui/icons-material/Check';
import CheckCircleIcon from '@mui/icons-material/CheckCircle';
import LockIcon from '@mui/icons-material/Lock';
import { JournalDetail } from '../types/transfer.types';
import { formatMinorUnitsToInr } from '../../shared/utils/money';

interface JournalInspectorProps {
  journal: JournalDetail;
  sourceLedgerAccountId: string;
  destinationLedgerAccountId: string;
}

export const JournalInspector: React.FC<JournalInspectorProps> = ({
  journal,
  sourceLedgerAccountId,
  destinationLedgerAccountId,
}) => {
  const [copiedId, setCopiedId] = useState<string | null>(null);

  const handleCopy = async (text: string) => {
    try {
      await navigator.clipboard.writeText(text);
      setCopiedId(text);
      setTimeout(() => setCopiedId(null), 2000);
    } catch {
      // Fallback
    }
  };

  // Exact BigInt calculation of total debits and credits
  let totalDebitsMinor = 0n;
  let totalCreditsMinor = 0n;

  for (const entry of journal.entries) {
    try {
      const amount = BigInt(entry.amountMinor);
      if (entry.direction === 'DEBIT') {
        totalDebitsMinor += amount;
      } else if (entry.direction === 'CREDIT') {
        totalCreditsMinor += amount;
      }
    } catch {
      // ignore
    }
  }

  const isBalanced = totalDebitsMinor > 0n && totalDebitsMinor === totalCreditsMinor;

  return (
    <Card elevation={0} sx={{ border: '1px solid', borderColor: 'divider', borderRadius: 2 }}>
      <CardContent sx={{ p: { xs: 2.5, sm: 3 } }}>
        <Stack
          direction={{ xs: 'column', sm: 'row' }}
          spacing={2}
          sx={{ justifyContent: 'space-between', alignItems: { sm: 'center' }, mb: 2 }}
        >
          <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center' }}>
            <Box
              sx={{
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                width: 36,
                height: 36,
                borderRadius: 1.5,
                bgcolor: 'primary.50',
                color: 'primary.main',
              }}
            >
              <LockIcon fontSize="small" />
            </Box>
            <Box>
              <Typography variant="h6" sx={{ fontWeight: 700 }}>
                Double-Entry Journal Inspector
              </Typography>
              <Typography variant="caption" color="text.secondary" sx={{ display: 'block' }}>
                Immutable financial posting record in ledger engine
              </Typography>
            </Box>
          </Stack>

          <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
            <Chip
              label={journal.status}
              color="primary"
              size="small"
              sx={{ fontWeight: 700, letterSpacing: 0.5 }}
            />
            {isBalanced && (
              <Chip
                icon={<CheckCircleIcon fontSize="small" />}
                label="Balanced (Debits = Credits)"
                color="success"
                size="small"
                variant="outlined"
                sx={{ fontWeight: 600 }}
              />
            )}
          </Stack>
        </Stack>

        <Box sx={{ mb: 2.5, p: 1.5, bgcolor: 'background.default', borderRadius: 1.5 }}>
          <Typography variant="caption" color="text.secondary" sx={{ display: 'block' }}>
            Journal Transaction ID
          </Typography>
          <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
            <Typography
              variant="body2"
              sx={{ fontFamily: 'monospace', fontWeight: 600, wordBreak: 'break-all' }}
            >
              {journal.journalTransactionId}
            </Typography>
            <Tooltip title={copiedId === journal.journalTransactionId ? 'Copied!' : 'Copy ID'}>
              <IconButton size="small" onClick={() => handleCopy(journal.journalTransactionId)}>
                {copiedId === journal.journalTransactionId ? (
                  <CheckIcon fontSize="small" color="success" />
                ) : (
                  <ContentCopyIcon fontSize="small" />
                )}
              </IconButton>
            </Tooltip>
          </Stack>
        </Box>

        <TableContainer sx={{ borderRadius: 1.5, border: '1px solid', borderColor: 'divider', mb: 2 }}>
          <Table size="small">
            <TableHead sx={{ bgcolor: 'action.hover' }}>
              <TableRow>
                <TableCell sx={{ fontWeight: 700 }}>Ledger Account ID</TableCell>
                <TableCell sx={{ fontWeight: 700 }}>Account Role</TableCell>
                <TableCell align="right" sx={{ fontWeight: 700 }}>Debit (INR)</TableCell>
                <TableCell align="right" sx={{ fontWeight: 700 }}>Credit (INR)</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {journal.entries.map((entry, idx) => {
                const isSource = entry.ledgerAccountId === sourceLedgerAccountId;
                const isDestination = entry.ledgerAccountId === destinationLedgerAccountId;
                const roleLabel = isSource
                  ? 'Source Wallet'
                  : isDestination
                  ? 'Destination Wallet'
                  : 'Ledger Account';

                const formattedAmount = formatMinorUnitsToInr(entry.amountMinor);

                return (
                  <TableRow key={idx} hover>
                    <TableCell sx={{ fontFamily: 'monospace', fontSize: '0.85rem' }}>
                      <Stack direction="row" spacing={0.5} sx={{ alignItems: 'center' }}>
                        <span>{entry.ledgerAccountId}</span>
                        <Tooltip title={copiedId === entry.ledgerAccountId ? 'Copied!' : 'Copy'}>
                          <IconButton
                            size="small"
                            onClick={() => handleCopy(entry.ledgerAccountId)}
                            sx={{ p: 0.25 }}
                          >
                            {copiedId === entry.ledgerAccountId ? (
                              <CheckIcon fontSize="inherit" color="success" />
                            ) : (
                              <ContentCopyIcon fontSize="inherit" />
                            )}
                          </IconButton>
                        </Tooltip>
                      </Stack>
                    </TableCell>
                    <TableCell>
                      <Chip
                        label={roleLabel}
                        size="small"
                        color={isSource ? 'default' : isDestination ? 'secondary' : 'default'}
                        variant="outlined"
                        sx={{ fontSize: '0.75rem', fontWeight: 600 }}
                      />
                    </TableCell>
                    <TableCell align="right" sx={{ fontFamily: 'monospace', fontWeight: 600 }}>
                      {entry.direction === 'DEBIT' ? formattedAmount : '—'}
                    </TableCell>
                    <TableCell align="right" sx={{ fontFamily: 'monospace', fontWeight: 600 }}>
                      {entry.direction === 'CREDIT' ? formattedAmount : '—'}
                    </TableCell>
                  </TableRow>
                );
              })}

              {/* Totals Summary Row */}
              <TableRow sx={{ bgcolor: 'action.selected' }}>
                <TableCell colSpan={2} sx={{ fontWeight: 700 }}>
                  Total (Double-Entry Invariant Check)
                </TableCell>
                <TableCell align="right" sx={{ fontFamily: 'monospace', fontWeight: 800 }}>
                  {formatMinorUnitsToInr(totalDebitsMinor.toString())}
                </TableCell>
                <TableCell align="right" sx={{ fontFamily: 'monospace', fontWeight: 800 }}>
                  {formatMinorUnitsToInr(totalCreditsMinor.toString())}
                </TableCell>
              </TableRow>
            </TableBody>
          </Table>
        </TableContainer>

        <Divider sx={{ my: 1.5 }} />

        <Stack direction="row" sx={{ justifyContent: 'space-between', alignItems: 'center' }}>
          <Typography variant="caption" color="text.secondary">
            Financial Invariant: &sum; Debits = &sum; Credits
          </Typography>
          <Typography variant="caption" color="text.secondary">
            Posted At: {journal.postedAt ? new Date(journal.postedAt).toUTCString() : 'N/A'}
          </Typography>
        </Stack>
      </CardContent>
    </Card>
  );
};
