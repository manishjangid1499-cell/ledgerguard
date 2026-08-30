# LedgerGuard Planned API Specification

> **Note**: This document outlines the planned REST API surface for LedgerGuard. Endpoint schemas and DTO structures are conceptual designs to guide future implementation phases (Phases 3–33).

---

## 1. Global API Conventions

- **Base URL Prefix**: `/api`
- **Content Type**: `application/json`
- **Error Format**: RFC-7807 `ProblemDetail` JSON structure
- **Authentication**: `Authorization: Bearer <access_token>` or secure session cookie
- **Idempotency**: All mutating financial endpoints accept an `Idempotency-Key: <UUID>` header

---

## 2. Authentication & Identity (`/api/auth`)

| Method | Endpoint | Access | Purpose |
| :--- | :--- | :--- | :--- |
| `POST` | `/api/auth/register` | Public | Register a new user account (Customer or Merchant). |
| `POST` | `/api/auth/login` | Public | Authenticate credentials; return JWT access token & set refresh cookie. |
| `POST` | `/api/auth/refresh` | Public / Refresh Token | Exchange valid refresh token for a new access token. |
| `POST` | `/api/auth/logout` | Authenticated | Revoke refresh token and invalidate session. |
| `GET` | `/api/auth/me` | Authenticated | Retrieve authenticated user profile and roles. |

---

## 3. Wallets & Balances (`/api/wallets`)

| Method | Endpoint | Access | Purpose |
| :--- | :--- | :--- | :--- |
| `GET` | `/api/wallets/me` | Customer / Merchant | Get current user's wallet summary, posted balance, active holds, and available balance. |
| `GET` | `/api/wallets/{walletId}/holds` | Customer / Merchant / Ops | List active and historical balance holds on the wallet. |

---

## 4. Peer-to-Peer Transfers (`/api/transfers`)

| Method | Endpoint | Access | Purpose |
| :--- | :--- | :--- | :--- |
| `POST` | `/api/transfers` | Customer | Initiate an atomic internal transfer to another customer wallet. *Requires `Idempotency-Key`*. |
| `GET` | `/api/transfers/{transferId}` | Customer (Owner) / Ops | Retrieve details and status of a transfer transaction. |
| `GET` | `/api/transfers` | Customer / Ops | List transfer history with pagination. |

---

## 5. Merchant Payments (`/api/payments`)

| Method | Endpoint | Access | Purpose |
| :--- | :--- | :--- | :--- |
| `POST` | `/api/payments` | Customer | Authorize and execute a payment to a merchant wallet. *Requires `Idempotency-Key`*. |
| `GET` | `/api/payments/{paymentId}` | Customer / Merchant / Ops | Retrieve payment status, metadata, and refund history. |
| `GET` | `/api/payments` | Merchant / Ops | List received merchant payments. |

---

## 6. Refunds (`/api/refunds`)

| Method | Endpoint | Access | Purpose |
| :--- | :--- | :--- | :--- |
| `POST` | `/api/payments/{paymentId}/refunds` | Merchant / Ops | Initiate a full or partial refund against a settled payment. *Requires `Idempotency-Key`*. |
| `GET` | `/api/payments/{paymentId}/refunds` | Merchant / Customer / Ops | List all refunds associated with a payment. |

---

## 7. External Funding & Deposits (`/api/funding`)

| Method | Endpoint | Access | Purpose |
| :--- | :--- | :--- | :--- |
| `POST` | `/api/funding/initiate` | Customer | Request external wallet top-up via simulated PSP gateway. |
| `GET` | `/api/funding/{fundingId}` | Customer (Owner) / Ops | Check status of in-flight or settled funding operation. |

---

## 8. External Payouts & Withdrawals (`/api/payouts`)

| Method | Endpoint | Access | Purpose |
| :--- | :--- | :--- | :--- |
| `POST` | `/api/payouts/initiate` | Customer / Merchant | Initiate withdrawal to external bank account (creates balance hold). |
| `GET` | `/api/payouts/{payoutId}` | Owner / Ops | Check payout settlement status. |

---

## 9. Webhooks & Ingress (`/api/webhooks`)

| Method | Endpoint | Access | Purpose |
| :--- | :--- | :--- | :--- |
| `POST` | `/api/webhooks/psp` | PSP Signature Verified | Receive asynchronous status events from PSP Simulator. |

---

## 10. Ledger & Journal Inspector (`/api/ledger`)

| Method | Endpoint | Access | Purpose |
| :--- | :--- | :--- | :--- |
| `GET` | `/api/ledger/transactions` | Authenticated (Owner Filtered) / Ops | Query immutable journal transactions and balanced debit/credit entries. |
| `GET` | `/api/ledger/transactions/{id}` | Owner / Ops | Inspect double-entry details of a specific journal transaction. |
| `GET` | `/api/ledger/accounts/{id}/statement` | Owner / Ops | Generate an account statement across a date range. |

---

## 11. Reconciliation & Operations (`/api/reconciliation`, `/api/ops`)

| Method | Endpoint | Access | Purpose |
| :--- | :--- | :--- | :--- |
| `POST` | `/api/reconciliation/run` | Ops | Trigger on-demand three-level reconciliation run. |
| `GET` | `/api/reconciliation/runs` | Ops | List historical reconciliation reports and discrepancy summaries. |
| `POST` | `/api/reconciliation/repair-snapshot` | Ops | Auto-repair corrupted account balance snapshot from immutable ledger. |
| `POST` | `/api/ops/failure-lab/run-scenario` | Ops | Execute an automated Money Integrity Failure Lab chaos scenario. |
| `GET` | `/api/ops/failure-lab/reports` | Ops | Retrieve invariant audit reports and failure lab test outcomes. |
| `GET` | `/api/ops/outbox/status` | Ops | Inspect transactional outbox backlog and queue lag. |
