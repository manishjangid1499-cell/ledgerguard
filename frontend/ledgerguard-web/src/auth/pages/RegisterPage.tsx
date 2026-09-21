import { RegisterForm } from '../components/RegisterForm';
import { AuthCard } from '../components/AuthCard';

export const RegisterPage = () => (
  <AuthCard
    title="Create account"
    description="Create your LedgerGuard account."
    alternateText="Already have an account?"
    alternateLabel="Sign in"
    alternateRoute="/login"
  >
    <RegisterForm />
  </AuthCard>
);
