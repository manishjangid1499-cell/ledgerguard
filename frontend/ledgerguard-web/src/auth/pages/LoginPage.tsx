import { Alert } from '@mui/material';
import { useLocation } from 'react-router-dom';
import { LoginForm } from '../components/LoginForm';
import { AuthCard } from '../components/AuthCard';

export const LoginPage = () => {
  const location = useLocation();
  const successMessage = (location.state as { message?: string } | null)?.message;
  return (
    <AuthCard title="Sign in to LedgerGuard" description="Enter your credentials to access your account."
      alternateText="Don't have an account?" alternateLabel="Create account" alternateRoute="/register">
      {successMessage && <Alert severity="success" sx={{ mb: 2.5 }}>{successMessage}</Alert>}
      <LoginForm />
    </AuthCard>
  );
};
