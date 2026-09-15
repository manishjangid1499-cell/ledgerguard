import { useEffect } from 'react';
import { useLocation } from 'react-router-dom';

export const RouteMetadata = () => {
  const { pathname } = useLocation();
  const titles: Record<string, string> = {
    '/': 'Payment Integrity & Ledger Platform', '/login': 'Sign in', '/register': 'Create account',
    '/app': 'Dashboard', '/profile': 'Account profile', '/app/failure-lab': 'Failure Lab',
  };
  const title = titles[pathname] || (pathname.startsWith('/app/transfers/') ? 'Transfer details' : 'Page not found');
  useEffect(() => { document.title = `${title} | LedgerGuard`; }, [title]);
  return <span className="sr-only" role="status">{title}</span>;
};
