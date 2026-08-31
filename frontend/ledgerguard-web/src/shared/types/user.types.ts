export type UserRole = 'CUSTOMER' | 'MERCHANT' | 'OPS';

export type UserStatus = 'ACTIVE' | 'DISABLED';

export interface UserSummary {
  id: string;
  email: string;
  role: UserRole;
  status: UserStatus;
  createdAt: string;
}
