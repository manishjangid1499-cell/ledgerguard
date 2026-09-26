import { Box, Button, Container, Paper, Typography } from '@mui/material';
import ScienceOutlinedIcon from '@mui/icons-material/ScienceOutlined';
import { Link as RouterLink } from 'react-router-dom';
import { useAuth } from '../../auth/hooks/useAuth';
import { CustomerDashboard } from '../../financial/components/CustomerDashboard';
import { MerchantDashboard } from '../../financial/components/MerchantDashboard';

export const AppHomePage = () => {
  const { user } = useAuth();

  if (user?.role === 'MERCHANT') {
    return (
      <Container maxWidth="lg">
        <MerchantDashboard />
      </Container>
    );
  }

  if (user?.role === 'CUSTOMER') {
    return (
      <Container maxWidth="lg">
        <CustomerDashboard />
      </Container>
    );
  }

  return (
    <Container maxWidth="lg">
      <Box sx={{ mb: 3 }}>
        <Typography variant="h4" component="h1" color="primary.main" sx={{ fontSize: { xs: '1.75rem', md: '2rem' }, mb: 0.75 }}>
          Operations
        </Typography>
        <Typography variant="body2" color="text.secondary">
          Review financial integrity through controlled resilience scenarios.
        </Typography>
      </Box>

      <Paper variant="outlined" sx={{ p: { xs: 3, sm: 4 } }}>
        <ScienceOutlinedIcon color="secondary" sx={{ fontSize: 32, mb: 2 }} />
        <Typography component="h2" variant="h6" sx={{ mb: 1 }}>Financial integrity testing</Typography>
        <Typography variant="body2" color="text.secondary" sx={{ maxWidth: 620, mb: 3, lineHeight: 1.7 }}>
          Run isolated fault scenarios and inspect their timelines and invariant checks in the Failure Lab.
        </Typography>
        {user?.role === 'OPS' && <Button component={RouterLink} to="/app/failure-lab" variant="contained">Open Failure Lab</Button>}
      </Paper>
    </Container>
  );
};
