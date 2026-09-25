import { useQuery } from '@tanstack/react-query';
import { Alert, Box, Container, Paper, Stack, Typography } from '@mui/material';
import { authApi } from '../../auth/api/authApi';
import { DataLoading } from '../components/DataLoading';
import { StatusBadge } from '../components/StatusBadge';
import { CopyButton } from '../components/CopyButton';
import { formatDateTime, formatRoleLabel } from '../utils/display';
import { getErrorMessage } from '../api/errorMessage';

export const ProfilePage = () => {
  const { data: user, isLoading, error } = useQuery({
    queryKey: ['currentUser'],
    queryFn: () => authApi.getMe(),
  });
  return (
    <Container maxWidth="md">
      <Typography variant="h4" component="h1" color="primary.main" sx={{ mb: 1, fontSize: { xs: '1.75rem', md: '2rem' } }}>
        Account profile
      </Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 3 }}>
        View your identity, account status and access level.
      </Typography>
      <Paper variant="outlined" sx={{ p: { xs: 2.5, md: 4 } }}>
        {isLoading && <DataLoading label="Loading account profile" minHeight={340} />}
        {error && <Alert severity="error">{getErrorMessage(error, 'Unable to load your profile. Please try again.')}</Alert>}
        {user && !error && (
          <>
            <Box component="dl" sx={{ m: 0 }}>
              {[
                ['Full name', user.fullName || '—'],
                ['Email address', user.email],
                ['Account type', formatRoleLabel(user.role)],
                ['Account status', <StatusBadge key="status" status={user.status} />],
                ['Member since', formatDateTime(user.createdAt)],
              ].map(([label, value]) => (
                <Box
                  key={String(label)}
                  sx={{
                    display: 'grid',
                    gridTemplateColumns: { xs: '1fr', sm: '160px 1fr' },
                    gap: 1,
                    py: 2,
                    borderBottom: '1px solid',
                    borderColor: 'divider',
                  }}
                >
                  <Typography component="dt" variant="body2" color="text.secondary">{label}</Typography>
                  <Typography component="dd" variant="body2" sx={{ m: 0, overflowWrap: 'anywhere', fontWeight: 500 }}>{value}</Typography>
                </Box>
              ))}
            </Box>

            <Box sx={{ mt: 3, pt: 2.5, borderTop: '1px solid', borderColor: 'divider' }}>
              <Typography variant="subtitle2" color="text.secondary" sx={{ textTransform: 'uppercase', letterSpacing: 0.5, fontSize: '0.75rem', mb: 1.5 }}>
                Account reference
              </Typography>
              <Box
                sx={{
                  display: 'grid',
                  gridTemplateColumns: { xs: '1fr', sm: '160px 1fr' },
                  gap: 1,
                  alignItems: 'center',
                }}
              >
                <Typography variant="body2" color="text.secondary">User ID</Typography>
                <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
                  <Typography variant="body2" sx={{ fontFamily: 'monospace', fontSize: '0.85rem' }}>
                    {user.id}
                  </Typography>
                  <CopyButton value={user.id} label="user ID" />
                </Stack>
              </Box>
            </Box>
          </>
        )}
      </Paper>
    </Container>
  );
};
