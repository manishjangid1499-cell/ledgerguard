# LedgerGuard Security Architecture & Policy

## 1. Security Architecture Principles

- **Authoritative Backend Security**: The backend API enforces all authentication and authorization rules. The frontend UI is treated as untrusted and client-side access controls are for user experience only.
- **No Distributed Auth Complexity**: Authentication and authorization are embedded directly within `ledgerguard-api`. No external authentication microservice or Keycloak cluster is introduced.
- **Defense in Depth**: Every request is authenticated, authorized against ownership boundaries, validated for payload structure, and rate-limited.

---

## 2. Role-Based Access Control (RBAC)

LedgerGuard defines three principal roles:

| Role | Target Identity | Permissions & Scope |
| :--- | :--- | :--- |
| **`ROLE_CUSTOMER`** | End users / Wallet owners | Manage personal profile; view own wallet; initiate peer-to-peer transfers; initiate deposits/withdrawals; view own journal entries and transaction history. |
| **`ROLE_MERCHANT`** | Commercial accounts | Manage merchant profile; accept payments; initiate refunds on owned payments; inspect merchant settlement ledger. |
| **`ROLE_OPS`** | Platform administrators & Operators | Access system-wide integrity metrics; execute reconciliation jobs; inspect outbox queues; trigger Money Integrity Failure Lab scenarios; view system audit logs. |

---

## 3. Authentication & Token Management

### Password Hashing
- User passwords are encrypted using **BCrypt** with an adaptive work factor (default `strength = 12`). Plaintext passwords never touch logs, storage, or external systems.

### Access & Refresh Token Design
- **Access Tokens**: Short-lived JSON Web Tokens (JWT) signed with HMAC-SHA256 or RSA-256 containing `sub` (User ID), `roles`, `issuedAt`, and `expiration` (e.g., 15-minute validity).
- **Refresh Tokens**: Long-lived, cryptographically random strings stored as hashed records in the PostgreSQL database (`refresh_tokens` table) with expiration timestamps and device/IP metadata.
- **Revocation & Rotation**: Refresh tokens are rotated upon each exchange. Revoking a refresh token immediately blocks subsequent token generation.

### Cookie & Storage Strategy
- In browser clients, refresh tokens are transported via `HttpOnly`, `Secure`, `SameSite=Strict` cookies to prevent client-side JavaScript access and XSS theft.
- Short-lived access tokens may be kept in memory or transmitted via the `Authorization: Bearer <token>` header.

### CSRF Protections
- State-changing REST endpoints enforce `SameSite=Strict` cookie policies and custom request header verification (`X-Requested-With` or custom CSRF tokens) where cookie-based authentication is utilized.

---

## 4. Resource Ownership & Authorization Checks

- **Owner-Only Resource Protection**: An authenticated user with `ROLE_CUSTOMER` cannot view, transfer from, or manipulate accounts or resources belonging to another user.
- **Declarative & Programmatic Checks**: Spring Security method security (`@PreAuthorize("hasRole('OPS') or #userId == authentication.principal.id")`) combined with domain-level repository filtering ensures users query only their own data.

---

## 5. Webhook Security & Tampering Prevention

- **HMAC Signature Verification**: Inbound webhooks from the PSP Simulator must include a cryptographic signature header (e.g., `X-Signature-SHA256`).
- **Timestamp Windows & Replay Protection**: Webhook payloads include a timestamp header. Payloads older than a strict tolerance window (e.g., 5 minutes) are rejected to prevent replay attacks.
- **Shared Secret Isolation**: Webhook signing secrets are stored in secure environment variables, never committed to source control.

---

## 6. Audit Logging & Sensitive Data Masking

- **Operations Audit Trail**: All privileged actions performed by `ROLE_OPS` (manual reconciliation overrides, account freezes, parameter modifications) generate immutable rows in `audit_events`.
- **Sensitive Data Logging Policy**:
  - Passwords, JWT secrets, full payment card numbers, bank account numbers, and webhook secrets are strictly prohibited from log files.
  - User identifiers, correlation IDs, and transaction IDs are logged for traceability without exposing personally identifiable information (PII).

---

## 7. Rate Limiting & Denial of Service Protection

- **API Ingress Throttling**: Authentication endpoints (`/api/auth/login`, `/api/auth/register`) and money-moving endpoints (`/api/transfers`, `/api/payments`) are subject to token-bucket rate limiting to mitigate brute-force and credential-stuffing attacks.
- **Bounded Concurrency**: Thread pool limits and database connection limits prevent resource exhaustion under distributed load.
