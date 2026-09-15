import { UserRole } from '../types/user.types';

export function formatRoleLabel(role: UserRole): string {
  return { CUSTOMER: 'Customer', MERCHANT: 'Merchant', OPS: 'Operations' }[role];
}

export function formatDateTime(value: string): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '—';
  return date.toLocaleString('en-IN', { dateStyle: 'medium', timeStyle: 'short' });
}
