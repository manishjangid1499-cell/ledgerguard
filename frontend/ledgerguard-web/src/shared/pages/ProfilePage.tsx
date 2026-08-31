import React from 'react';
import { useQuery } from '@tanstack/react-query';
import {
  Container,
  Paper,
  Typography,
  Box,
  Divider,
  Stack,
  Chip,
  CircularProgress,
  Alert,
  List,
  ListItem,
  ListItemText,
} from '@mui/material';
import PersonIcon from '@mui/icons-material/Person';
import { authApi } from '../../auth/api/authApi';
import { UserSummary, UserRole } from '../types/user.types';

function formatRoleLabel(role: UserRole): string {
  switch (role) {
    case 'CUSTOMER':
      return 'Customer';
    case 'MERCHANT':
      return 'Merchant';
    case 'OPS':
      return 'Operations';
    default:
      return role;
  }
}

function formatDate(isoString?: string): string {
  if (!isoString) return '—';
  try {
    return new Date(isoString).toUTCString();
  } catch {
    return isoString;
  }
}

export const ProfilePage: React.FC = () => {
  const { data: user, isLoading, error } = useQuery<UserSummary>({
    queryKey: ['currentUser'],
    queryFn: () => authApi.getMe(),
  });

  return (
    <Container maxWidth="md">
      <Paper
        elevation={0}
        sx={{
          p: { xs: 3, md: 4 },
          border: '1px solid',
          borderColor: 'divider',
          borderRadius: 2,
          bgcolor: 'background.paper',
        }}
      >
        <Stack direction="row" spacing={2} sx={{ alignItems: 'center', mb: 2 }}>
          <PersonIcon color="primary" sx={{ fontSize: 36 }} />
          <Box>
            <Typography variant="h5" component="h1" sx={{ fontWeight: 700 }}>
              User Profile
            </Typography>
            <Typography variant="body2" color="text.secondary">
              Authoritative identity information from LedgerGuard Security Server
            </Typography>
          </Box>
        </Stack>

        <Divider sx={{ my: 2.5 }} />

        {isLoading && (
          <Box sx={{ display: 'flex', justifyContent: 'center', py: 4 }}>
            <CircularProgress size={32} />
          </Box>
        )}

        {error && (
          <Alert severity="error" sx={{ mb: 2 }}>
            Unable to load profile data from server.
          </Alert>
        )}

        {user && (
          <List disablePadding>
            <ListItem divider sx={{ px: 0, py: 1.5 }}>
              <ListItemText
                primary="User ID"
                secondary={user.id}
                slotProps={{
                  primary: { variant: 'caption', color: 'text.secondary', sx: { fontWeight: 600 } },
                  secondary: { variant: 'body1', color: 'text.primary', sx: { fontFamily: 'monospace' } },
                }}
              />
            </ListItem>

            <ListItem divider sx={{ px: 0, py: 1.5 }}>
              <ListItemText
                primary="Email address"
                secondary={user.email}
                slotProps={{
                  primary: { variant: 'caption', color: 'text.secondary', sx: { fontWeight: 600 } },
                  secondary: { variant: 'body1', color: 'text.primary' },
                }}
              />
            </ListItem>

            <ListItem divider sx={{ px: 0, py: 1.5 }}>
              <ListItemText
                primary="Account Role"
                secondary={
                  <Box sx={{ mt: 0.5 }}>
                    <Chip
                      label={formatRoleLabel(user.role)}
                      color={user.role === 'MERCHANT' ? 'secondary' : user.role === 'OPS' ? 'info' : 'primary'}
                      variant="outlined"
                      size="small"
                      sx={{ fontWeight: 600 }}
                    />
                  </Box>
                }
                slotProps={{
                  primary: { variant: 'caption', color: 'text.secondary', sx: { fontWeight: 600 } },
                }}
              />
            </ListItem>

            <ListItem divider sx={{ px: 0, py: 1.5 }}>
              <ListItemText
                primary="Account Status"
                secondary={
                  <Box sx={{ mt: 0.5 }}>
                    <Chip
                      label={user.status}
                      color={user.status === 'ACTIVE' ? 'success' : 'default'}
                      variant="outlined"
                      size="small"
                      sx={{ fontWeight: 600 }}
                    />
                  </Box>
                }
                slotProps={{
                  primary: { variant: 'caption', color: 'text.secondary', sx: { fontWeight: 600 } },
                }}
              />
            </ListItem>

            <ListItem sx={{ px: 0, py: 1.5 }}>
              <ListItemText
                primary="Account Created At (UTC)"
                secondary={formatDate(user.createdAt)}
                slotProps={{
                  primary: { variant: 'caption', color: 'text.secondary', sx: { fontWeight: 600 } },
                  secondary: { variant: 'body1', color: 'text.primary' },
                }}
              />
            </ListItem>
          </List>
        )}
      </Paper>
    </Container>
  );
};
