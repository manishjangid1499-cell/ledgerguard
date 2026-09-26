import { useEffect, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
  Chip,
  FormControl,
  Grid,
  IconButton,
  InputAdornment,
  InputLabel,
  MenuItem,
  Select,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TablePagination,
  TableRow,
  TextField,
  Tooltip,
  Typography,
} from '@mui/material';
import RefreshIcon from '@mui/icons-material/Refresh';
import SearchIcon from '@mui/icons-material/Search';
import ClearIcon from '@mui/icons-material/Clear';
import { Link as RouterLink, useSearchParams } from 'react-router-dom';
import { financialApi } from '../api';
import { FinancialDomain, Page, PaymentStatus, RefundStatus } from '../types';
import { financialError } from '../feedback';
import { DataLoading } from '../../shared/components/DataLoading';
import { StatusBadge } from '../../shared/components/StatusBadge';
import { CopyButton } from '../../shared/components/CopyButton';
import { formatMinorUnitsToInr } from '../../shared/utils/money';
import { formatDateTime } from '../../shared/utils/display';

interface HistoryRow {
  id: string;
  amount: string;
  fee?: string;
  net?: string;
  status: string;
  createdAt: string;
  completedAt?: string | null;
  refundedAmountMinor?: string;
  refundStatus?: RefundStatus;
}

export const FinancialHistory = ({ domain, merchant = false }: { domain: FinancialDomain; merchant?: boolean }) => {
  const [searchParams, setSearchParams] = useSearchParams();
  const isPayments = domain === 'payments';

  // Read URL query parameters for filter persistence
  const pageParam = parseInt(searchParams.get('page') || '0', 10);
  const searchParam = searchParams.get('search') || '';
  const statusParam = (searchParams.get('status') as PaymentStatus) || '';
  const sortParam = (searchParams.get('sort') as 'newest' | 'oldest') || 'newest';

  const [page, setPage] = useState(isNaN(pageParam) || pageParam < 0 ? 0 : pageParam);
  const [searchInput, setSearchInput] = useState(searchParam);
  const [debouncedSearch, setDebouncedSearch] = useState(searchParam);
  const [statusFilter, setStatusFilter] = useState<PaymentStatus | ''>(statusParam);
  const [sortOrder, setSortOrder] = useState<'newest' | 'oldest'>(sortParam);

  // Debounce search input
  useEffect(() => {
    const handler = setTimeout(() => {
      setDebouncedSearch(searchInput.trim());
    }, 300);
    return () => clearTimeout(handler);
  }, [searchInput]);

  // Sync state to URL search parameters
  useEffect(() => {
    if (!isPayments) return;
    const nextParams = new URLSearchParams(searchParams);
    if (page > 0) nextParams.set('page', String(page)); else nextParams.delete('page');
    if (debouncedSearch) nextParams.set('search', debouncedSearch); else nextParams.delete('search');
    if (statusFilter) nextParams.set('status', statusFilter); else nextParams.delete('status');
    if (sortOrder === 'oldest') nextParams.set('sort', 'oldest'); else nextParams.delete('sort');

    // Only update if changed
    if (nextParams.toString() !== searchParams.toString()) {
      setSearchParams(nextParams, { replace: true });
    }
  }, [page, debouncedSearch, statusFilter, sortOrder, isPayments, searchParams, setSearchParams]);

  const query = useQuery({
    queryKey: [domain, 'list', page, debouncedSearch, statusFilter, sortOrder],
    queryFn: async (): Promise<Page<HistoryRow>> => {
      if (domain === 'payments') {
        const result = await financialApi.payments({
          page,
          size: 10,
          paymentId: debouncedSearch || undefined,
          status: statusFilter || undefined,
          sort: sortOrder,
        });
        return {
          ...result,
          items: result.items.map(item => ({
            id: item.paymentId,
            amount: item.grossAmountMinor,
            fee: item.feeAmountMinor,
            net: item.merchantNetAmountMinor,
            status: item.status,
            createdAt: item.createdAt,
            completedAt: item.completedAt,
            refundedAmountMinor: item.refundedAmountMinor,
            refundStatus: item.refundStatus,
          })),
        };
      }
      const result = domain === 'funding' ? await financialApi.funding(page) : await financialApi.payouts(page);
      return {
        ...result,
        items: result.items.map(item => ({
          id: 'fundingId' in item ? item.fundingId : item.payoutId,
          amount: item.amountMinor,
          status: item.status,
          createdAt: item.createdAt,
          completedAt: item.completedAt,
        })),
      };
    },
    staleTime: 15_000,
  });

  const title = domain === 'payments'
    ? (merchant ? 'Customer payments' : 'Merchant payments')
    : domain === 'funding'
    ? 'Funding history'
    : (merchant ? 'Payout history' : 'Withdrawal history');

  const subtitle = domain === 'payments' && merchant ? 'Payments customers make to your business using Pay merchant.' : undefined;
  const empty = domain === 'payments'
    ? (merchant ? 'No customer payments found.' : 'No payments found.')
    : domain === 'funding'
    ? 'No funding activity yet.'
    : (merchant ? 'No payouts yet.' : 'No withdrawals yet.');

  const data = query.data;
  const hasActiveFilters = Boolean(debouncedSearch || statusFilter || sortOrder !== 'newest');

  const handleClearFilters = () => {
    setSearchInput('');
    setDebouncedSearch('');
    setStatusFilter('');
    setSortOrder('newest');
    setPage(0);
  };

  return (
    <Card>
      <CardContent sx={{ p: { xs: 2.5, sm: 3 } }}>
        <Stack direction="row" spacing={1} sx={{ justifyContent: 'space-between', alignItems: 'center', mb: 2 }}>
          <Box>
            <Typography component="h2" variant="h6" sx={{ mb: subtitle ? 0.5 : 0 }}>
              {title}
            </Typography>
            {subtitle && <Typography variant="body2" color="text.secondary">{subtitle}</Typography>}
          </Box>
          <Tooltip title={`Refresh ${title.toLowerCase()}`}>
            <span>
              <IconButton
                aria-label={`Refresh ${title.toLowerCase()}`}
                disabled={query.isFetching}
                onClick={() => { void query.refetch(); }}
              >
                <RefreshIcon fontSize="small" />
              </IconButton>
            </span>
          </Tooltip>
        </Stack>

        {/* Filter & Search Bar for Payments */}
        {isPayments && (
          <Box sx={{ mb: 2.5, p: 2, bgcolor: 'background.default', borderRadius: 1 }}>
            <Grid container spacing={1.5} sx={{ alignItems: 'center' }}>
              <Grid size={{ xs: 12, sm: 5, md: 4 }}>
                <TextField
                  fullWidth
                  size="small"
                  label="Search by Payment ID"
                  placeholder="Enter 36-character UUID"
                  value={searchInput}
                  onChange={e => {
                    setSearchInput(e.target.value);
                    setPage(0);
                  }}
                  slotProps={{
                    input: {
                      startAdornment: (
                        <InputAdornment position="start">
                          <SearchIcon fontSize="small" color="action" />
                        </InputAdornment>
                      ),
                      endAdornment: searchInput ? (
                        <InputAdornment position="end">
                          <IconButton size="small" aria-label="Clear search" onClick={() => { setSearchInput(''); setPage(0); }}>
                            <ClearIcon fontSize="small" />
                          </IconButton>
                        </InputAdornment>
                      ) : undefined,
                    },
                  }}
                />
              </Grid>
              <Grid size={{ xs: 6, sm: 3.5, md: 3 }}>
                <FormControl fullWidth size="small">
                  <InputLabel id="status-filter-label">Status</InputLabel>
                  <Select
                    labelId="status-filter-label"
                    value={statusFilter}
                    label="Status"
                    onChange={e => {
                      setStatusFilter(e.target.value as PaymentStatus | '');
                      setPage(0);
                    }}
                  >
                    <MenuItem value="">All statuses</MenuItem>
                    <MenuItem value="SUCCEEDED">Succeeded</MenuItem>
                    <MenuItem value="PROCESSING">Processing</MenuItem>
                    <MenuItem value="CREATED">Created</MenuItem>
                    <MenuItem value="FAILED">Failed</MenuItem>
                  </Select>
                </FormControl>
              </Grid>
              <Grid size={{ xs: 6, sm: 3.5, md: 3 }}>
                <FormControl fullWidth size="small">
                  <InputLabel id="sort-order-label">Sort</InputLabel>
                  <Select
                    labelId="sort-order-label"
                    value={sortOrder}
                    label="Sort"
                    onChange={e => {
                      setSortOrder(e.target.value as 'newest' | 'oldest');
                      setPage(0);
                    }}
                  >
                    <MenuItem value="newest">Newest first</MenuItem>
                    <MenuItem value="oldest">Oldest first</MenuItem>
                  </Select>
                </FormControl>
              </Grid>
              {hasActiveFilters && (
                <Grid size={{ xs: 12, md: 2 }}>
                  <Button
                    variant="text"
                    size="small"
                    startIcon={<ClearIcon fontSize="small" />}
                    onClick={handleClearFilters}
                    sx={{ color: 'text.secondary' }}
                  >
                    Clear filters
                  </Button>
                </Grid>
              )}
            </Grid>
          </Box>
        )}

        {query.isLoading ? (
          <DataLoading label={`Loading ${title.toLowerCase()}`} />
        ) : query.isError ? (
          <Alert severity="error">{financialError(query.error, `Unable to load ${title.toLowerCase()}. Please try again.`)}</Alert>
        ) : !data ? (
          <Alert severity="error">These records are unavailable. Please try again.</Alert>
        ) : !data.items.length ? (
          <Box sx={{ py: 5, textAlign: 'center' }}>
            <Typography variant="body2" color="text.secondary" sx={{ fontWeight: 500 }}>
              {hasActiveFilters ? 'No payments match your search or filter criteria.' : empty}
            </Typography>
            {hasActiveFilters && (
              <Button size="small" onClick={handleClearFilters} sx={{ mt: 1 }}>
                Reset filters
              </Button>
            )}
          </Box>
        ) : (
          <>
            {/* Mobile Cards */}
            <Box sx={{ display: { xs: 'block', md: 'none' } }}>
              {data.items.map(row => (
                <Box key={row.id} sx={{ py: 2, borderTop: '1px solid', borderColor: 'divider' }}>
                  <Stack direction="row" useFlexGap spacing={1} sx={{ flexWrap: 'wrap', justifyContent: 'space-between', alignItems: 'center' }}>
                    <Typography variant="body2" sx={{ fontWeight: 700, fontVariantNumeric: 'tabular-nums', overflowWrap: 'anywhere' }}>
                      {formatMinorUnitsToInr(row.amount)}
                    </Typography>
                    <StatusBadge status={row.status} />
                  </Stack>
                  {isPayments && merchant && (
                    <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5 }}>
                      Fee {formatMinorUnitsToInr(row.fee)} · Net {formatMinorUnitsToInr(row.net)}
                    </Typography>
                  )}
                  {row.refundStatus && row.refundStatus !== 'NOT_REFUNDED' && (
                    <Typography variant="caption" color="warning.main" sx={{ display: 'block', fontWeight: 600, mt: 0.25 }}>
                      {row.refundStatus === 'FULLY_REFUNDED' ? 'Fully refunded' : `${formatMinorUnitsToInr(row.refundedAmountMinor ?? '0')} refunded`}
                    </Typography>
                  )}
                  <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 0.75 }}>
                    {formatDateTime(row.createdAt)}
                  </Typography>
                  <Stack direction="row" spacing={1} sx={{ alignItems: 'center', my: 1 }}>
                    <Typography variant="caption" sx={{ fontFamily: 'monospace', overflowWrap: 'anywhere' }}>
                      {row.id}
                    </Typography>
                    <CopyButton value={row.id} label={`${isPayments ? 'payment' : 'record'} ID`} />
                  </Stack>
                  <Button component={RouterLink} to={`/app/${domain}/${row.id}`} size="small" aria-label={`View ${domain} record ${row.id}`}>
                    View details
                  </Button>
                </Box>
              ))}
            </Box>

            {/* Desktop Table */}
            <TableContainer tabIndex={0} role="region" aria-label={`${title}, scroll horizontally for more columns`} sx={{ display: { xs: 'none', md: 'block' } }}>
              <Table aria-label={title} sx={{ minWidth: isPayments ? (merchant ? 960 : 760) : 700 }}>
                <TableHead>
                  <TableRow>
                    <TableCell>{isPayments ? 'Payment ID' : domain === 'payouts' ? (merchant ? 'Payout ID' : 'Withdrawal ID') : 'Funding ID'}</TableCell>
                    <TableCell align="right">{isPayments ? (merchant ? 'Gross' : 'Amount paid') : 'Amount'}</TableCell>
                    {isPayments && merchant && (
                      <>
                        <TableCell align="right">Platform fee</TableCell>
                        <TableCell align="right">Merchant net</TableCell>
                      </>
                    )}
                    {isPayments && (
                      <TableCell>Refunded</TableCell>
                    )}
                    <TableCell>Status</TableCell>
                    <TableCell>{domain === 'payouts' ? 'Requested' : 'Date'}</TableCell>
                    {domain === 'payouts' && <TableCell>Completed</TableCell>}
                    <TableCell align="right">Details</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {data.items.map(row => (
                    <TableRow key={row.id} hover>
                      <TableCell>
                        <Stack direction="row" spacing={0.5} sx={{ alignItems: 'center' }}>
                          <Tooltip title={row.id}>
                            <Typography variant="caption" sx={{ fontFamily: 'monospace' }}>
                              {row.id.slice(0, 8)}…{row.id.slice(-4)}
                            </Typography>
                          </Tooltip>
                          <CopyButton value={row.id} label={`${isPayments ? 'payment' : 'record'} ID`} />
                        </Stack>
                      </TableCell>
                      <TableCell align="right" sx={{ whiteSpace: 'nowrap', fontVariantNumeric: 'tabular-nums', fontWeight: 600 }}>
                        {formatMinorUnitsToInr(row.amount)}
                      </TableCell>
                      {isPayments && merchant && (
                        <>
                          <TableCell align="right" sx={{ whiteSpace: 'nowrap', fontVariantNumeric: 'tabular-nums' }}>
                            {formatMinorUnitsToInr(row.fee)}
                          </TableCell>
                          <TableCell align="right" sx={{ whiteSpace: 'nowrap', fontVariantNumeric: 'tabular-nums', fontWeight: 600, color: 'secondary.main' }}>
                            {formatMinorUnitsToInr(row.net)}
                          </TableCell>
                        </>
                      )}
                      {isPayments && (
                        <TableCell sx={{ whiteSpace: 'nowrap' }}>
                          {row.refundStatus === 'FULLY_REFUNDED' ? (
                            <Chip size="small" label="Fully refunded" color="warning" variant="outlined" sx={{ fontWeight: 600 }} />
                          ) : row.refundStatus === 'PARTIALLY_REFUNDED' ? (
                            <Chip size="small" label={`${formatMinorUnitsToInr(row.refundedAmountMinor ?? '0')} refunded`} color="warning" sx={{ fontWeight: 600 }} />
                          ) : (
                            <Typography variant="caption" color="text.secondary">Not refunded</Typography>
                          )}
                        </TableCell>
                      )}
                      <TableCell>
                        <StatusBadge status={row.status} />
                      </TableCell>
                      <TableCell sx={{ whiteSpace: 'nowrap' }}>
                        <Typography variant="caption">{formatDateTime(row.createdAt)}</Typography>
                      </TableCell>
                      {domain === 'payouts' && (
                        <TableCell sx={{ whiteSpace: 'nowrap' }}>
                          <Typography variant="caption">{row.completedAt ? formatDateTime(row.completedAt) : '—'}</Typography>
                        </TableCell>
                      )}
                      <TableCell align="right">
                        <Button component={RouterLink} to={`/app/${domain}/${row.id}`} size="small" aria-label={`View details for ${domain} record ${row.id}`}>
                          View details
                        </Button>
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </TableContainer>
          </>
        )}

        {data && !query.isError && data.totalElements > 0 && (
          <TablePagination
            component="div"
            count={data.totalElements}
            page={data.page}
            rowsPerPage={10}
            rowsPerPageOptions={[10]}
            onPageChange={(_, next) => setPage(next)}
            showFirstButton
            showLastButton
            aria-label="Pagination controls"
            sx={{ '.MuiTablePagination-toolbar': { px: 0 }, '.MuiTablePagination-actions': { ml: 1 } }}
          />
        )}
      </CardContent>
    </Card>
  );
};
