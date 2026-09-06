import React from 'react';
import { Navigate, Outlet } from 'react-router-dom';
import { useAuth } from '../../auth/hooks/useAuth';
import { LoadingScreen } from '../../shared/components/LoadingScreen';

export const OpsRoute: React.FC = () => {
  const { user, status } = useAuth();

  if (status === 'loading') {
    return <LoadingScreen message="Checking operations authorization..." />;
  }

  if (status === 'unauthenticated' || !user) {
    return <Navigate to="/login" replace />;
  }

  if (user.role !== 'OPS') {
    return <Navigate to="/app" replace />;
  }

  return <Outlet />;
};
