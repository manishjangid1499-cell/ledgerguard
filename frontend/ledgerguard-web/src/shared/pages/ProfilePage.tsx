import { useQuery } from '@tanstack/react-query';
import { Alert, Box, Container, Paper, Typography } from '@mui/material';
import { authApi } from '../../auth/api/authApi';
import { DataLoading } from '../components/DataLoading';
import { StatusBadge } from '../components/StatusBadge';
import { formatDateTime, formatRoleLabel } from '../utils/display';
import { getErrorMessage } from '../api/errorMessage';

export const ProfilePage = () => {
  const { data: user, isLoading, error } = useQuery({
    queryKey: ['currentUser'],
    queryFn: () => authApi.getMe(),
  });
  return (
    <Container maxWidth="md">
      <Typography variant="h4" component="h1" color="primary.main" sx={{ mb: 1, fontSize: { xs: '1.75rem', md: '2rem' } }}>Account profile</Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 3 }}>Your account details and access level.</Typography>
      <Paper variant="outlined" sx={{ p: { xs: 2.5, md: 4 } }}>
        {isLoading && <DataLoading label="Loading account profile" minHeight={340} />}
        {error && <Alert severity="error">{getErrorMessage(error, 'Unable to load your profile. Please try again.')}</Alert>}
        {user && !error && (
          <Box component="dl" sx={{ m: 0 }}>
            {[
              ['Email address', user.email],
              ['Account type', formatRoleLabel(user.role)],
              ['Account status', <StatusBadge status={user.status} />],
              ['Member since', formatDateTime(user.createdAt)],
              ['User ID', <Box component="span" sx={{ fontFamily: 'monospace', fontSize: '0.875rem' }}>{user.id}</Box>],
            ].map(([label, value]) => (
              <Box key={String(label)} sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', sm: '160px 1fr' }, gap: 1, py: 2,
                borderBottom: '1px solid', borderColor: 'divider', '&:last-child': { borderBottom: 0 } }}>
                <Typography component="dt" variant="body2" color="text.secondary">{label}</Typography>
                <Typography component="dd" variant="body2" sx={{ m: 0, overflowWrap: 'anywhere', fontWeight: 500 }}>{value}</Typography>
              </Box>
            ))}
          </Box>
        )}
      </Paper>
    </Container>
  );
};
