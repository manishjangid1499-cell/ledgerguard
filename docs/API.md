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
| `INVALID_FUNDING` | `400 Bad Request` | Funding request validation failed (invalid amount, non-integral value, or invalid headers). |
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

## 5. Transfers Endpoints (`/api/transfers`) — Implemented in Phase 10

### 5.1 `POST /api/transfers`
- **Access**: `CUSTOMER`, `MERCHANT` (authenticated; OPS forbidden)
- **Headers**:
  - `Authorization: Bearer <access_token>` (required)
  - `Idempotency-Key: <UUID/String>` (required, max 128 chars)
- **Request Body**:
  ```json
  {
    "destinationWalletId": "6ba7b810-9dad-11d1-80b4-00c04fd430c8",
    "amountMinor": 250000
  }
  ```
- **Response (201 Created on first execution / 200 OK on idempotency replay)**:
  ```json
  {
    "transferId": "1f8e1234-5678-9abc-def0-123456789abc",
    "sourceLedgerAccountId": "6ba7b810-9dad-11d1-80b4-00c04fd430c7",
    "destinationLedgerAccountId": "6ba7b810-9dad-11d1-80b4-00c04fd430c8",
    "amountMinor": "250000",
    "currency": "INR",
    "journalTransactionId": "550e8400-e29b-41d4-a716-446655440000",
    "createdAt": "2026-08-31T22:00:00.000Z",
    "replayed": false
  }
  ```
- **Error Responses**:
  - `400 Bad Request` (`INVALID_TRANSFER` / `VALIDATION_FAILED`): Missing/blank Idempotency-Key (>128 chars), non-positive amount, self-transfer, closed account, non-user system account target.
  - `401 Unauthorized` (`AUTHENTICATION_REQUIRED`): Missing or invalid Bearer token.
  - `403 Forbidden` (`ACCESS_DENIED`): Caller with `OPS` role.
  - `404 Not Found` (`RESOURCE_NOT_FOUND`): Nonexistent destination ledger account.
  - `409 Conflict` (`INSUFFICIENT_FUNDS` / `IDEMPOTENCY_CONFLICT` / `IDEMPOTENCY_OPERATION_IN_PROGRESS`): Source wallet has insufficient funds (`balance < amount`), reusing Idempotency-Key with different payload parameters, or concurrent in-flight request with same key.

---

## 6. Financial Read APIs (Implemented in Phase 12)

### 6.1 Get Current User Wallet (`GET /api/wallets/me`)
- **Access**: `CUSTOMER`, `MERCHANT` (`OPS` returns `403 Forbidden`)
- **Success Response (200 OK)**:
  ```json
  {
    "ledgerAccountId": "6ba7b810-9dad-11d1-80b4-00c04fd430c7",
    "accountType": "CUSTOMER",
    "currency": "INR",
    "status": "ACTIVE",
    "balanceMinor": "125000",
    "activeHoldAmountMinor": "25000",
    "availableBalanceMinor": "100000"
  }
  ```
- **Fields**:
  - `balanceMinor`: Immutable posted balance snapshot in paise (decimal string).
  - `activeHoldAmountMinor`: Sum of all currently `ACTIVE` holds in paise (decimal string).
  - `availableBalanceMinor`: Spendable capacity (`balanceMinor - activeHoldAmountMinor`) in paise (decimal string, may be negative).

### 6.2 Get Transfer History (`GET /api/transfers?page=0&size=20`)
- **Access**: `CUSTOMER`, `MERCHANT`
- **Query Params**: `page` (default 0), `size` (default 20, max 50)
- **Success Response (200 OK)**:
  ```json
  {
    "items": [
      {
        "transferId": "1f8e1234-5678-9abc-def0-123456789abc",
        "sourceLedgerAccountId": "6ba7b810-9dad-11d1-80b4-00c04fd430c7",
        "destinationLedgerAccountId": "6ba7b810-9dad-11d1-80b4-00c04fd430c8",
        "amountMinor": "10000",
        "currency": "INR",
        "journalTransactionId": "550e8400-e29b-41d4-a716-446655440000",
        "createdAt": "2026-08-31T22:00:00.000Z",
        "direction": "OUTGOING"
      }
    ],
    "page": 0,
    "size": 20,
    "totalElements": 1,
    "totalPages": 1
  }
  ```

### 6.3 Get Transfer Detail & Journal Inspector (`GET /api/transfers/{transferId}`)
- **Access**: `CUSTOMER`, `MERCHANT` (Only source or destination wallet owner; unrelated returns `404 Not Found`)
- **Success Response (200 OK)**:
  ```json
  {
    "transferId": "1f8e1234-5678-9abc-def0-123456789abc",
    "sourceLedgerAccountId": "6ba7b810-9dad-11d1-80b4-00c04fd430c7",
    "destinationLedgerAccountId": "6ba7b810-9dad-11d1-80b4-00c04fd430c8",
    "amountMinor": "10000",
    "currency": "INR",
    "journalTransactionId": "550e8400-e29b-41d4-a716-446655440000",
    "createdAt": "2026-08-31T22:00:00.000Z",
    "direction": "OUTGOING",
    "journal": {
      "journalTransactionId": "550e8400-e29b-41d4-a716-446655440000",
      "status": "POSTED",
      "postedAt": "2026-08-31T22:00:00.000Z",
      "entries": [
        {
          "ledgerAccountId": "6ba7b810-9dad-11d1-80b4-00c04fd430c7",
          "direction": "DEBIT",
          "amountMinor": "10000"
        },
        {
          "ledgerAccountId": "6ba7b810-9dad-11d1-80b4-00c04fd430c8",
          "direction": "CREDIT",
          "amountMinor": "10000"
        }
      ]
    }
  }
  ```

---

## 7. Merchant Payment Endpoints (`/api/payments`) — Implemented in Phase 13

### 7.1 `POST /api/payments`
- **Access**: Authenticated `CUSTOMER` only (`MERCHANT` and `OPS` return 403 Forbidden).
- **Headers**:
  - `Authorization: Bearer <access_token>`
  - `Idempotency-Key: <unique-string-1-to-128-chars>` (Required)
- **Request Body**:
  ```json
  {
    "merchantLedgerAccountId": "6ba7b810-9dad-11d1-80b4-00c04fd430c8",
    "amountMinor": "10000",
    "currency": "INR"
  }
  ```
- **Response (201 Created / 200 OK on Idempotent Replay)**:
  ```json
  {
    "id": "e4b2d184-3c6f-4f2a-89a1-7e8c3b2a1e0f",
    "customerUserId": "82185e28-975f-488d-a034-342e13db43c4",
    "customerLedgerAccountId": "6ba7b810-9dad-11d1-80b4-00c04fd430c7",
    "merchantLedgerAccountId": "6ba7b810-9dad-11d1-80b4-00c04fd430c8",
    "grossAmountMinor": "10000",
    "feeAmountMinor": "100",
    "merchantNetAmountMinor": "9900",
    "currency": "INR",
    "status": "SUCCEEDED",
    "journalTransactionId": "d5a3412d-8903-4c4e-8159-7d39751804aa",
    "createdAt": "2026-09-01T12:00:00.000Z",
    "completedAt": "2026-09-01T12:00:00.050Z"
  }
  ```
- **Platform Fee Allocation**:
  - 100 bps (1%) computed via integer arithmetic with floor rounding (`(gross * 100) / 10000`).
  - Zero floating point arithmetic.
  - Multi-line balanced posting:
    - Customer Wallet: `DEBIT grossAmountMinor`
    - Merchant Wallet: `CREDIT merchantNetAmountMinor`
    - Platform Fee Account: `CREDIT feeAmountMinor` (omitted when `feeAmountMinor == 0`).
  - Total Debits == Total Credits.

---

## 8. Payment Refund Endpoints (`/api/payments/{paymentId}/refund`)

### 8.1 Execute Payment Refund
- **Method / Path**: `POST /api/payments/{paymentId}/refund`
- **Access**: `MERCHANT` (Only the authenticated owner of the payment's merchant wallet)
- **Headers**:
  - `Authorization: Bearer <token>`
  - `Idempotency-Key: <1-128 chars>` (Required)
- **Request Body**:
  ```json
  {
    "amountMinor": 2500
  }
  ```
- **Validation**:
  - `amountMinor`: Non-null, strictly positive integer (`> 0`).
  - `paymentId`: Must reference an existing payment where `status = 'SUCCEEDED'`.
  - Unrelated merchant attempting refund receives `404 Not Found` (anti-enumeration / IDOR protection).
  - Cumulative refund limit: $\text{alreadyRefunded} + \text{requestedRefund} \le \text{grossAmountMinor}$. If exceeded, returns `409 Conflict` with `REFUND_LIMIT_EXCEEDED`.
- **Response**:
  - Status: `201 Created` (first execution) / `200 OK` (idempotent replay)
  - Body:
  ```json
  {
    "refundId": "0b15e219-c16e-4ad2-a9b8-085795cb70f0",
    "paymentId": "d38bb39b-eec7-4632-8418-8f8319f3a611",
    "refundAmountMinor": "2500",
    "merchantDebitAmountMinor": "2475",
    "feeDebitAmountMinor": "25",
    "currency": "INR",
    "journalTransactionId": "a90bb6e2-26cb-4cf8-a92c-567c2ce91244",
    "createdAt": "2026-09-01T12:00:00.000Z",
    "replayed": false
  }
  ```
- **Compensating Journal Posting**:
  - Customer Wallet: `CREDIT refundAmountMinor`
  - Merchant Wallet: `DEBIT merchantDebitAmountMinor` (omitted if 0)
  - Platform Fee Account: `DEBIT feeDebitAmountMinor` (omitted if 0)
  - Total Debits == Total Credits.

---

## 9. Planned Domain Endpoints Roadmap (Phases 15–33)

### 9.1 Wallets & Balances (`/api/wallets`)
| Method | Endpoint | Access | Purpose |
| :--- | :--- | :--- | :--- |
| `GET` | `/api/wallets/{walletId}/holds` | Customer / Merchant / Ops | List active and historical balance holds on the wallet. |

### 9.2 Merchant Payments Read APIs (`/api/payments`)
| Method | Endpoint | Access | Purpose |
| :--- | :--- | :--- | :--- |
| `GET` | `/api/payments/{paymentId}` | Customer / Merchant / Ops | Retrieve payment status, metadata, and refund history. |
| `GET` | `/api/payments` | Merchant / Ops | List received merchant payments. |

### 9.3 External Funding & Deposits (`/api/funding`)
| Method | Endpoint | Access | Purpose |
| :--- | :--- | :--- | :--- |
| `POST` | `/api/funding/initiate` | Customer | Request external wallet top-up via simulated PSP gateway. |
| `GET` | `/api/funding/{fundingId}` | Customer (Owner) / Ops | Check status of in-flight or settled funding operation. |

### 9.4 External Payouts & Withdrawals (`/api/payouts`)
| Method | Endpoint | Access | Purpose |
| :--- | :--- | :--- | :--- |
| `POST` | `/api/payouts/initiate` | Customer / Merchant | Initiate withdrawal to external bank account (creates balance hold). |
| `GET` | `/api/payouts/{payoutId}` | Owner / Ops | Check payout settlement status. |

### 9.5 Webhooks & Ingress (`/api/webhooks`)
| Method | Endpoint | Access | Purpose |
| :--- | :--- | :--- | :--- |
| `POST` | `/api/webhooks/psp` | PSP Signature Verified | Receive asynchronous status events from PSP Simulator. |

### 9.6 Ledger & Journal Inspector (`/api/ledger`)
| Method | Endpoint | Access | Purpose |
| :--- | :--- | :--- | :--- |
| `GET` | `/api/ledger/transactions` | Authenticated (Owner Filtered) / Ops | Query immutable journal transactions and balanced debit/credit entries. |
| `GET` | `/api/ledger/transactions/{id}` | Owner / Ops | Inspect double-entry details of a specific journal transaction. |
| `GET` | `/api/ledger/accounts/{id}/statement` | Owner / Ops | Generate an account statement across a date range. |

### 9.7 Reconciliation & Operations (`/api/reconciliation`, `/api/ops`)
| Method | Endpoint | Access | Purpose |
| :--- | :--- | :--- | :--- |
| `POST` | `/api/reconciliation/run` | Ops | Trigger on-demand three-level reconciliation run. |
| `GET` | `/api/reconciliation/runs` | Ops | List historical reconciliation reports and discrepancy summaries. |
| `POST` | `/api/reconciliation/repair-snapshot` | Ops | Auto-repair corrupted account balance snapshot from immutable ledger. |
| `POST` | `/api/ops/failure-lab/run-scenario` | Ops | Execute an automated Money Integrity Failure Lab chaos scenario. |
| `GET` | `/api/ops/failure-lab/reports` | Ops | Retrieve invariant audit reports and failure lab test outcomes. |
| `GET` | `/api/ops/outbox/status` | Ops | Inspect transactional outbox backlog and queue lag. |

---

## 10. Internal Architecture & Event Delivery Note (Phase 16 & 17)
- **HTTP Contracts Unchanged**: The Transactional Outbox and Kafka Publisher operate purely at the backend persistence, asynchronous messaging, and event publication layer. Public API request and response contracts for Transfers, Payments, Refunds, and Wallets remain completely unchanged.
- **Kafka Event Delivery (Phase 17)**: Committed outbox events are claimed via `FOR UPDATE SKIP LOCKED` and published to Kafka topic `ledgerguard.domain-events.v1` in CloudEvents 1.0 structured JSON format with key `aggregate_id.toString()`.
- **At-Least-Once Delivery**: Delivery guarantees are at-least-once. Stable event IDs (`outbox_events.id`) allow idempotent consumer deduplication. Aggregate ID key provides partition affinity, while message ordering within partition matches broker append order.

---

## 11. External PSP Simulator API (`psp-simulator:8081`) (Phase 19)

### 11.1 Provider Operations API (`/api/provider/operations`)
| Method | Endpoint | Status Code | Purpose |
| :--- | :--- | :--- | :--- |
| `POST` | `/api/provider/operations` | `201 Created` / `200 OK` | Submit provider operation. Returns 201 on first creation; 200 on matching replay; 409 on conflicting replay. |
| `GET` | `/api/provider/operations/{id}` | `200 OK` / `404 Not Found` | Retrieve provider operation by providerOperationId. |
| `GET` | `/api/provider/operations/by-client/{clientOperationId}` | `200 OK` / `404 Not Found` | Retrieve provider operation by clientOperationId (used for status recovery). |

#### Request Schema:
```json
{
  "clientOperationId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "operationType": "CREDIT",
  "amountMinor": "10000",
  "currency": "INR",
  "webhookUrl": "http://localhost:8080/api/webhooks/psp"
}
```

#### Response Schema:
```json
{
  "providerOperationId": "c8b417e2-45e3-4d69-a359-2c708fa8d10b",
  "clientOperationId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "operationType": "CREDIT",
  "status": "SUCCEEDED",
  "amountMinor": "10000",
  "currency": "INR",
  "createdAt": "2026-09-01T12:00:00Z",
  "completedAt": "2026-09-01T12:00:00Z"
}
```

### 11.2 Simulator Scenario Control API (`/api/simulator/scenarios`)
| Method | Endpoint | Status Code | Purpose |
| :--- | :--- | :--- | :--- |
| `PUT` | `/api/simulator/scenarios/{clientOperationId}` | `200 OK` | Inject deterministic fault scenario for a specific clientOperationId. |

#### Request Schema:
```json
{
  "scenario": "TIMEOUT_AFTER_SUCCESS",
  "delayMs": 1000,
  "temporaryFailureCount": 0
}
```
*Supported Scenarios:* `NORMAL_SUCCESS`, `TIMEOUT_AFTER_SUCCESS`, `DELAYED_WEBHOOK`, `DUPLICATE_WEBHOOK`, `TEMPORARY_500`.

### 11.3 Webhook Payload Schema
```json
{
  "eventId": "e9b2075a-73ea-4dfc-ba1c-fbefc3547f2a",
  "eventType": "PROVIDER_OPERATION_SUCCEEDED",
  "providerOperationId": "c8b417e2-45e3-4d69-a359-2c708fa8d10b",
  "clientOperationId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "operationType": "CREDIT",
  "status": "SUCCEEDED",
  "amountMinor": "10000",
  "currency": "INR",
  "occurredAt": "2026-09-01T12:00:00Z"
}
```

---

## 12. External Wallet Funding API (`/api/funding`) — Phase 20

### 12.1 Fund Customer Wallet
| Method | Endpoint | Status Code | Required Role | Purpose |
| :--- | :--- | :--- | :--- | :--- |
| `POST` | `/api/funding` | `201 Created` / `200 OK` / `202 Accepted` | `CUSTOMER` | Request external wallet funding top-up via external PSP. |

- **Headers:**
  - `Authorization: Bearer <customer_jwt>`
  - `Idempotency-Key: <UUID>` (Required, max 128 characters)
- **Status Codes:**
  - `201 Created`: Authoritative provider confirmation received; customer wallet credited and double-entry journal posted on first attempt.
  - `200 OK`: Idempotent replay of already settled funding operation (0 additional PSP calls, 0 additional ledger credits).
  - `202 Accepted`: Provider call unconfirmed (timeout or 5xx server error). FundingOperation created and preserved in `PROCESSING` status with 0 wallet credit.

#### Request Schema:
```json
{
  "amountMinor": "10000"
}
```

#### Response Schema (201 Created / 200 OK):
```json
{
  "fundingId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "status": "SUCCEEDED",
  "amountMinor": "10000",
  "currency": "INR",
  "providerOperationId": "c8b417e2-45e3-4d69-a359-2c708fa8d10b",
  "journalTransactionId": "f1b417e2-45e3-4d69-a359-2c708fa8d10c",
  "replayed": false
}
```

#### Response Schema (202 Accepted):
```json
{
  "fundingId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "status": "PROCESSING",
  "amountMinor": "10000",
  "currency": "INR",
  "providerOperationId": null,
  "journalTransactionId": null,
  "replayed": false
}
```

---

## 13. External Payouts / Withdrawals API (`/api/payouts`) — Phase 21

### 13.1 Request Outbound Payout / Withdrawal
| Method | Endpoint | Status Code | Required Role | Purpose |
| :--- | :--- | :--- | :--- | :--- |
| `POST` | `/api/payouts` | `201 Created` / `200 OK` / `202 Accepted` | `CUSTOMER`, `MERCHANT` | Request external wallet payout/withdrawal via balance hold reservation and external PSP. |

- **Headers:**
  - `Authorization: Bearer <jwt>`
  - `Idempotency-Key: <UUID>` (Required, max 128 characters)
- **Status Codes:**
  - `201 Created`: Authoritative provider `SUCCEEDED` confirmation received; hold consumed and double-entry settlement journal posted (`DEBIT` source wallet, `CREDIT` `PSP_CLEARING`).
  - `200 OK`: Idempotent replay of already settled `SUCCEEDED` or `FAILED` payout (0 additional PSP calls, 0 additional ledger movements).
  - `202 Accepted`: Ambiguous provider outcome (timeout or malformed response). Payout preserved in `PROCESSING` status with balance hold remaining `ACTIVE`. Definite provider failures (`FAILED` response) release hold and return 200/201 with `FAILED` payout status.

#### Request Schema:
```json
{
  "amountMinor": "10000"
}
```

#### Response Schema (201 Created / 200 OK - Succeeded):
```json
{
  "payoutId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "status": "SUCCEEDED",
  "amountMinor": "10000",
  "currency": "INR",
  "balanceHoldId": "7cb417e2-45e3-4d69-a359-2c708fa8d10a",
  "providerOperationId": "c8b417e2-45e3-4d69-a359-2c708fa8d10b",
  "journalTransactionId": "f1b417e2-45e3-4d69-a359-2c708fa8d10c",
  "replayed": false
}
```

#### Response Schema (202 Accepted - In Flight / Ambiguous):
```json
{
  "payoutId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "status": "PROCESSING",
  "amountMinor": "10000",
  "currency": "INR",
  "balanceHoldId": "7cb417e2-45e3-4d69-a359-2c708fa8d10a",
  "providerOperationId": null,
  "journalTransactionId": null,
  "replayed": false
}
```
