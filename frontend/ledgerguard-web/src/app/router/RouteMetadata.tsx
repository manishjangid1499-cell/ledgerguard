import { useEffect } from 'react';
import { useLocation } from 'react-router-dom';

export const RouteMetadata = () => {
  const { pathname } = useLocation();
  const titles: Record<string, string> = {
    '/': 'Payment Integrity & Ledger Platform', '/login': 'Sign in', '/register': 'Create account',
    '/app': 'Dashboard', '/profile': 'Account profile', '/app/failure-lab': 'Failure Lab',
    '/app/activity': 'Activity', '/app/funding': 'Funding', '/app/payments': 'Payments', '/app/payouts': 'Payouts',
  };
  const details = [['transfers', 'Transfer'], ['funding', 'Funding'], ['payments', 'Payment'], ['payouts', 'Payout']]
    .find(([path]) => pathname.startsWith(`/app/${path}/`));
  const title = titles[pathname] || (details ? `${details[1]} details` : 'Page not found');
  useEffect(() => { document.title = `${title} | LedgerGuard`; }, [title]);
  return <span className="sr-only" role="status">{title}</span>;
};
