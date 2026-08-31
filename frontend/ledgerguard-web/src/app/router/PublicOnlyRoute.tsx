import React from 'react';
import { Navigate, Outlet } from 'react-router-dom';
import { useAuth } from '../../auth/hooks/useAuth';
import { LoadingScreen } from '../../shared/components/LoadingScreen';

export const PublicOnlyRoute: React.FC = () => {
  const { status } = useAuth();

  if (status === 'loading') {
    return <LoadingScreen message="Checking session credentials..." />;
  }

  if (status === 'authenticated') {
    return <Navigate to="/app" replace />;
  }

  return <Outlet />;
};
