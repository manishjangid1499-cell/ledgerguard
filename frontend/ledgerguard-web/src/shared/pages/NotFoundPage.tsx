import React from 'react';
import { Container, Paper, Typography, Button } from '@mui/material';
import { Link as RouterLink } from 'react-router-dom';
import { useAuth } from '../../auth/hooks/useAuth';

export const NotFoundPage: React.FC = () => {
  const { status } = useAuth();
  const targetRoute = status === 'authenticated' ? '/app' : '/';
  const targetLabel = status === 'authenticated' ? 'Return to Dashboard' : 'Return to Home';

  return (
    <Container maxWidth="sm" sx={{ py: 8 }}>
      <Paper
        elevation={0}
        sx={{
          p: 5,
          textAlign: 'center',
          border: '1px solid',
          borderColor: 'divider',
          borderRadius: 2,
          bgcolor: 'background.paper',
        }}
      >
        <Typography variant="h2" component="h1" color="primary.main" sx={{ fontWeight: 800, mb: 1 }}>
          404
        </Typography>
        <Typography variant="h5" gutterBottom sx={{ fontWeight: 600 }}>
          Page not found
        </Typography>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 3.5, maxWidth: 380, mx: 'auto' }}>
          The page you are looking for does not exist or has been moved.
        </Typography>
        <Button component={RouterLink} to={targetRoute} variant="contained" color="primary">
          {targetLabel}
        </Button>
      </Paper>
    </Container>
  );
};
