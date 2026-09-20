import { Navigate, Outlet } from 'react-router-dom';
import { useAuth } from '../../auth/hooks/useAuth';

// Navigation guard only; the API enforces roles and resource ownership independently.
export const FinancialRoute = ({ customerOnly = false }: { customerOnly?: boolean }) => {
  const { user } = useAuth();
  const allowed = user?.role === 'CUSTOMER' || (!customerOnly && user?.role === 'MERCHANT');
  return allowed ? <Outlet /> : <Navigate to="/app" replace />;
};
