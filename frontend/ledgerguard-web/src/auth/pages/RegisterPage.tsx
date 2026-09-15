import { RegisterForm } from '../components/RegisterForm';
import { AuthCard } from '../components/AuthCard';

export const RegisterPage = () => (
  <AuthCard title="Create your account" description="Access secure wallet and payment workflows."
    alternateText="Already have an account?" alternateLabel="Sign in" alternateRoute="/login">
    <RegisterForm />
  </AuthCard>
);
