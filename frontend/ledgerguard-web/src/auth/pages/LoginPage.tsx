import { Alert } from '@mui/material';
import { useLocation } from 'react-router-dom';
import { LoginForm } from '../components/LoginForm';
import { AuthCard } from '../components/AuthCard';

export const LoginPage = () => {
  const location = useLocation();
  const successMessage = (location.state as { message?: string } | null)?.message;
  return (
    <AuthCard
      title="Sign in"
      description="Access your LedgerGuard account."
      alternateText="New to LedgerGuard?"
      alternateLabel="Create account"
      alternateRoute="/register"
    >
      {successMessage && <Alert severity="success" sx={{ mb: 2 }}>{successMessage}</Alert>}
      <LoginForm />
    </AuthCard>
  );
};
