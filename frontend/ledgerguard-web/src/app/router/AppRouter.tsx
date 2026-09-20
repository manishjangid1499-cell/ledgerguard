import React from 'react';
import { BrowserRouter, Routes, Route } from 'react-router-dom';
import { PublicLayout } from '../../shared/layout/PublicLayout';
import { AppLayout } from '../../shared/layout/AppLayout';
import { LandingPage } from '../../shared/pages/LandingPage';
import { LoginPage } from '../../auth/pages/LoginPage';
import { RegisterPage } from '../../auth/pages/RegisterPage';
import { NotFoundPage } from '../../shared/pages/NotFoundPage';
import { ProtectedRoute } from './ProtectedRoute';
import { PublicOnlyRoute } from './PublicOnlyRoute';
import { OpsRoute } from './OpsRoute';
import { RouteMetadata } from './RouteMetadata';
import { FinancialRoute } from './FinancialRoute';

const AppHomePage = React.lazy(() => import('../../shared/pages/AppHomePage').then(module => ({ default: module.AppHomePage })));
const ProfilePage = React.lazy(() => import('../../shared/pages/ProfilePage').then(module => ({ default: module.ProfilePage })));
const TransferDetailPage = React.lazy(() => import('../../transfer/pages/TransferDetailPage').then(module => ({ default: module.TransferDetailPage })));
const FailureLabPage = React.lazy(() => import('../../failure-lab/pages/FailureLabPage').then(module => ({ default: module.FailureLabPage })));
const ActivityPage = React.lazy(() => import('../../financial/pages/ActivityPage').then(module => ({ default: module.ActivityPage })));
const ProviderOperationPage = React.lazy(() => import('../../financial/pages/ProviderOperationPage').then(module => ({ default: module.ProviderOperationPage })));
const PaymentDetailPage = React.lazy(() => import('../../financial/pages/PaymentDetailPage').then(module => ({ default: module.PaymentDetailPage })));

export const AppRouter: React.FC = () => {
  return (
    <BrowserRouter>
      <RouteMetadata />
      <Routes>
        {/* Public Landing Route */}
        <Route element={<PublicLayout />}>
          <Route path="/" element={<LandingPage />} />
        </Route>

        {/* Public-Only Auth Routes (redirects authenticated users to /app) */}
        <Route element={<PublicOnlyRoute />}>
          <Route element={<PublicLayout />}>
            <Route path="/login" element={<LoginPage />} />
            <Route path="/register" element={<RegisterPage />} />
          </Route>
        </Route>

        {/* Protected Routes (redirects unauthenticated users to /login) */}
        <Route element={<ProtectedRoute />}>
          <Route element={<AppLayout />}>
            <Route path="/app" element={<AppHomePage />} />
            <Route path="/app/transfers/:transferId" element={<TransferDetailPage />} />
            <Route path="/profile" element={<ProfilePage />} />
            <Route element={<FinancialRoute />}>
              <Route path="/app/activity" element={<ActivityPage />} />
              <Route path="/app/payments" element={<ActivityPage domain="payments" />} />
              <Route path="/app/payments/:paymentId" element={<PaymentDetailPage />} />
              <Route path="/app/payouts" element={<ActivityPage domain="payouts" />} />
              <Route path="/app/payouts/:operationId" element={<ProviderOperationPage domain="payouts" />} />
            </Route>
            <Route element={<FinancialRoute customerOnly />}>
              <Route path="/app/funding" element={<ActivityPage domain="funding" />} />
              <Route path="/app/funding/:operationId" element={<ProviderOperationPage domain="funding" />} />
            </Route>
            <Route element={<OpsRoute />}>
              <Route path="/app/failure-lab" element={<FailureLabPage />} />
            </Route>
          </Route>
        </Route>

        {/* 404 Catch-All Route */}
        <Route path="*" element={<NotFoundPage />} />
      </Routes>
    </BrowserRouter>
  );
};
