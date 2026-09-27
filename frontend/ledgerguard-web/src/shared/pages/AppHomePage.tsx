import { Container } from '@mui/material';
import { useAuth } from '../../auth/hooks/useAuth';
import { CustomerDashboard } from '../../financial/components/CustomerDashboard';
import { MerchantDashboard } from '../../financial/components/MerchantDashboard';
import { OpsDashboard } from '../../ops/components/OpsDashboard';

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

  if (user?.role === 'OPS') {
    return (
      <Container maxWidth="lg">
        <OpsDashboard />
      </Container>
    );
  }

  return null;
};
