# LedgerGuard API Specification

> **Note**: This document defines the API conventions, standardized error models, operational endpoints, and domain endpoint specifications for LedgerGuard.

---

## 1. Global API Conventions

- **Base URL Prefix**: `/api`
- **Standard Content Type**: `application/json`
- **Error Content Type**: `application/problem+json` (RFC 9457 Problem Details)
- **Authentication**: `Authorization: Bearer <access_token>` header for stateless requests; `ledgerguard_refresh_token` HttpOnly cookie for session rotation.
- **Idempotency**: All mutating financial endpoints accept an `Idempotency-Key: <UUID>` header (Phase 9+)

---

## 2. Operational & Health Endpoints (`/actuator`)

| Method | Endpoint | Access | Purpose |
| :--- | :--- | :--- | :--- |
| `GET` | `/actuator/health` | Public | High-level system health status (`{"status": "UP"}`). |
| `GET` | `/actuator/health/liveness` | Public | Liveness probe indicating application process is running. |
| `GET` | `/actuator/health/readiness` | Public | Readiness probe indicating application is ready to accept traffic. |
| `GET` | `/actuator/info` | Public | Application metadata and build information. |

> **Security Note**: In accordance with the principle of least privilege, management endpoints such as `/actuator/env`, `/actuator/beans`, `/actuator/mappings`, and `/actuator/configprops` are strictly unexposed.

---

## 3. Standardized Error Handling (RFC 9457 Problem Details)

All API error responses use `application/problem+json` and follow the standard RFC 9457 structure augmented with generic error codes and UTC timestamps.

### Generic & Security API Error Codes
| Error Code | HTTP Status | Description |
| :--- | :--- | :--- |
| `VALIDATION_FAILED` | `400 Bad Request` | Request payload failed Jakarta Bean Validation constraints or attempted forbidden role registration. |
| `EMAIL_ALREADY_REGISTERED` | `400 Bad Request` | Registration email already exists in system. |
| `MALFORMED_REQUEST` | `400 Bad Request` | Request payload contains malformed JSON or unreadable HTTP body. |
| `AUTHENTICATION_REQUIRED` | `401 Unauthorized` | Missing, invalid, or expired Bearer token on protected resource. |
| `INVALID_CREDENTIALS` | `401 Unauthorized` | Invalid email or password; disabled user account. |
| `INVALID_REFRESH_TOKEN` | `401 Unauthorized` | Refresh token is missing, malformed, expired, or revoked. |
| `ACCESS_DENIED` | `403 Forbidden` | Authenticated principal lacks necessary role/permission. |
| `RESOURCE_NOT_FOUND` | `404 Not Found` | Requested route or resource does not exist. |
| `INTERNAL_ERROR` | `500 Internal Server Error` | An unexpected server-side exception occurred. Sanitized safe detail returned. |

### Error Response Schema Example
```json
{
  "type": "about:blank",
  "title": "Validation failed",
  "status": 400,
  "detail": "One or more request fields are invalid.",
  "instance": "/api/auth/register",
  "errorCode": "VALIDATION_FAILED",
  "timestamp": "2026-08-31T12:00:00.000Z",
  "errors": [
    {
      "field": "email",
      "message": "must be a well-formed email address"
    },
    {
      "field": "password",
      "message": "password must be between 12 and 128 characters"
    }
  ]
}
```

### Information Disclosure Prevention
- Server-side exceptions (500) always produce sanitized details (`"An unexpected error occurred."`).
- Java exception class names, internal package structures, stack traces, and sensitive rejected input values (passwords, tokens, card details) are **never** included in HTTP error responses.
- Full exception details are logged securely on the server via standard SLF4J logging.

---

## 4. Authentication & Identity Endpoints (`/api/auth`) — Implemented in Phase 4

### 4.1 `POST /api/auth/register`
- **Access**: Public
- **Request Body**:
  ```json
  {
    "email": "alice@example.com",
    "password": "SecurePassword123!",
    "role": "CUSTOMER"
  }
  ```
  *(Permitted roles: `CUSTOMER`, `MERCHANT`. `OPS` registration is strictly forbidden).*
- **Response (201 Created)**:
  ```json
  {
    "id": "82185e28-975f-488d-a034-342e13db43c4",
    "email": "alice@example.com",
    "role": "CUSTOMER",
    "status": "ACTIVE",
    "createdAt": "2026-08-31T12:00:00.000Z"
  }
  ```

### 4.2 `POST /api/auth/login`
- **Access**: Public
- **Request Body**:
  ```json
  {
    "email": "alice@example.com",
    "password": "SecurePassword123!"
  }
  ```
- **Response (200 OK)**:
  - **Set-Cookie Header**: `ledgerguard_refresh_token=<token>; Path=/api/auth; HttpOnly; SameSite=Strict; Max-Age=604800`
  - **JSON Body**:
    ```json
    {
      "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
      "tokenType": "Bearer",
      "expiresIn": 900,
      "user": {
        "id": "82185e28-975f-488d-a034-342e13db43c4",
        "email": "alice@example.com",
        "role": "CUSTOMER",
        "status": "ACTIVE",
        "createdAt": "2026-08-31T12:00:00.000Z"
      }
    }
    ```

### 4.3 `POST /api/auth/refresh`
- **Access**: Public (via `ledgerguard_refresh_token` Cookie)
- **Response (200 OK)**:
  - Rotates refresh token cookie with new single-use token.
  - Returns fresh access token JSON response.

### 4.4 `POST /api/auth/logout`
- **Access**: Public / Authenticated (via `ledgerguard_refresh_token` Cookie)
- **Response (204 No Content)**:
  - Atomically marks active refresh token as revoked in database.
  - Clears `ledgerguard_refresh_token` cookie (`Max-Age=0`).

### 4.5 `GET /api/auth/me`
- **Access**: Authenticated (`Authorization: Bearer <token>`)
- **Response (200 OK)**:
  ```json
  {
    "id": "82185e28-975f-488d-a034-342e13db43c4",
    "email": "alice@example.com",
    "role": "CUSTOMER",
    "status": "ACTIVE",
    "createdAt": "2026-08-31T12:00:00.000Z"
  }
  ```

---

## 5. Planned Domain Endpoints Roadmap (Phases 6–33)

### 5.1 Wallets & Balances (`/api/wallets`)
| Method | Endpoint | Access | Purpose |
| :--- | :--- | :--- | :--- |
| `GET` | `/api/wallets/me` | Customer / Merchant | Get current user's wallet summary, posted balance, active holds, and available balance. |
| `GET` | `/api/wallets/{walletId}/holds` | Customer / Merchant / Ops | List active and historical balance holds on the wallet. |

### 5.2 Peer-to-Peer Transfers (`/api/transfers`)
| Method | Endpoint | Access | Purpose |
| :--- | :--- | :--- | :--- |
| `POST` | `/api/transfers` | Customer | Initiate an atomic internal transfer to another customer wallet. *Requires `Idempotency-Key`*. |
| `GET` | `/api/transfers/{transferId}` | Customer (Owner) / Ops | Retrieve details and status of a transfer transaction. |
| `GET` | `/api/transfers` | Customer / Ops | List transfer history with pagination. |

### 5.3 Merchant Payments (`/api/payments`)
| Method | Endpoint | Access | Purpose |
| :--- | :--- | :--- | :--- |
| `POST` | `/api/payments` | Customer | Authorize and execute a payment to a merchant wallet. *Requires `Idempotency-Key`*. |
| `GET` | `/api/payments/{paymentId}` | Customer / Merchant / Ops | Retrieve payment status, metadata, and refund history. |
| `GET` | `/api/payments` | Merchant / Ops | List received merchant payments. |

### 5.4 Refunds (`/api/refunds`)
| Method | Endpoint | Access | Purpose |
| :--- | :--- | :--- | :--- |
| `POST` | `/api/payments/{paymentId}/refunds` | Merchant / Ops | Initiate a full or partial refund against a settled payment. *Requires `Idempotency-Key`*. |
| `GET` | `/api/payments/{paymentId}/refunds` | Merchant / Customer / Ops | List all refunds associated with a payment. |

### 5.5 External Funding & Deposits (`/api/funding`)
| Method | Endpoint | Access | Purpose |
| :--- | :--- | :--- | :--- |
| `POST` | `/api/funding/initiate` | Customer | Request external wallet top-up via simulated PSP gateway. |
| `GET` | `/api/funding/{fundingId}` | Customer (Owner) / Ops | Check status of in-flight or settled funding operation. |

### 5.6 External Payouts & Withdrawals (`/api/payouts`)
| Method | Endpoint | Access | Purpose |
| :--- | :--- | :--- | :--- |
| `POST` | `/api/payouts/initiate` | Customer / Merchant | Initiate withdrawal to external bank account (creates balance hold). |
| `GET` | `/api/payouts/{payoutId}` | Owner / Ops | Check payout settlement status. |

### 5.7 Webhooks & Ingress (`/api/webhooks`)
| Method | Endpoint | Access | Purpose |
| :--- | :--- | :--- | :--- |
| `POST` | `/api/webhooks/psp` | PSP Signature Verified | Receive asynchronous status events from PSP Simulator. |

### 5.8 Ledger & Journal Inspector (`/api/ledger`)
| Method | Endpoint | Access | Purpose |
| :--- | :--- | :--- | :--- |
| `GET` | `/api/ledger/transactions` | Authenticated (Owner Filtered) / Ops | Query immutable journal transactions and balanced debit/credit entries. |
| `GET` | `/api/ledger/transactions/{id}` | Owner / Ops | Inspect double-entry details of a specific journal transaction. |
| `GET` | `/api/ledger/accounts/{id}/statement` | Owner / Ops | Generate an account statement across a date range. |

### 5.9 Reconciliation & Operations (`/api/reconciliation`, `/api/ops`)
| Method | Endpoint | Access | Purpose |
| :--- | :--- | :--- | :--- |
| `POST` | `/api/reconciliation/run` | Ops | Trigger on-demand three-level reconciliation run. |
| `GET` | `/api/reconciliation/runs` | Ops | List historical reconciliation reports and discrepancy summaries. |
| `POST` | `/api/reconciliation/repair-snapshot` | Ops | Auto-repair corrupted account balance snapshot from immutable ledger. |
| `POST` | `/api/ops/failure-lab/run-scenario` | Ops | Execute an automated Money Integrity Failure Lab chaos scenario. |
| `GET` | `/api/ops/failure-lab/reports` | Ops | Retrieve invariant audit reports and failure lab test outcomes. |
| `GET` | `/api/ops/outbox/status` | Ops | Inspect transactional outbox backlog and queue lag. |
