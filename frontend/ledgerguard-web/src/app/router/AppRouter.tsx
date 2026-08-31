import React from 'react';
import { BrowserRouter, Routes, Route } from 'react-router-dom';
import { PublicLayout } from '../../shared/layout/PublicLayout';
import { AppLayout } from '../../shared/layout/AppLayout';
import { LandingPage } from '../../shared/pages/LandingPage';
import { LoginPage } from '../../auth/pages/LoginPage';
import { RegisterPage } from '../../auth/pages/RegisterPage';
import { AppHomePage } from '../../shared/pages/AppHomePage';
import { ProfilePage } from '../../shared/pages/ProfilePage';
import { NotFoundPage } from '../../shared/pages/NotFoundPage';
import { ProtectedRoute } from './ProtectedRoute';
import { PublicOnlyRoute } from './PublicOnlyRoute';

export const AppRouter: React.FC = () => {
  return (
    <BrowserRouter>
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
            <Route path="/profile" element={<ProfilePage />} />
          </Route>
        </Route>

        {/* 404 Catch-All Route */}
        <Route path="*" element={<NotFoundPage />} />
      </Routes>
    </BrowserRouter>
  );
};
