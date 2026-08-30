# ADR-006: Authoritative Database-Backed Idempotency

## Status
Accepted

## Context
Network communication is inherently unreliable. In financial platforms, a client may submit a payment or transfer request, the server may process and commit the transaction, but the network connection may drop before the HTTP response reaches the client. If the client retries the request naively, a duplicate payment or transfer would be created.

To guarantee that money-mutating operations execute **at most once**, the system requires robust idempotency semantics. Relying on an in-memory cache or non-durable key-value store introduces race conditions and risks duplicate execution during server restarts or cache evictions.

## Decision
We implement **Database-Backed Authoritative Idempotency with Request Fingerprinting**:

1. **Unique Idempotency Key**:
   - Clients provide an `Idempotency-Key: <UUID>` header on mutating financial endpoints (`POST /transfers`, `POST /payments`, `POST /refunds`, `POST /funding/initiate`, `POST /payouts/initiate`).
2. **Authoritative Storage (`idempotency_records`)**:
   - Every idempotency key is stored in PostgreSQL with a composite unique constraint: `UNIQUE (client_id, idempotency_key)`.
3. **Request Fingerprinting**:
   - When a request arrives, the server computes a SHA-256 cryptographic hash of the canonical request method, target URI, and request body.
4. **Semantics & Conflict Resolution**:
   - **New Request**: An idempotency record is created with status `PROCESSING`. The business logic executes within the database transaction and updates the record to `COMPLETED` with the serialized response body and HTTP status code.
   - **Identical Retry**: If a request with the same `(client_id, idempotency_key)` and matching SHA-256 fingerprint arrives after completion, the server returns the cached response directly without re-executing any business logic.
   - **Conflicting Payload**: If a request arrives with an existing key but a *different* SHA-256 fingerprint, the server immediately rejects the request with `HTTP 409 Conflict` (Idempotency Key Conflict).
   - **In-Flight Concurrent Race**: If a concurrent duplicate request arrives while the original is still `PROCESSING`, the unique constraint blocks or rejects the duplicate with `HTTP 409 Conflict` (Operation In Progress).

## Alternatives Considered
1. **Redis-Only Idempotency Keys**:
   - *Rejected*: A Redis crash or key eviction could allow duplicate execution. Redis operations cannot participate atomically in the primary PostgreSQL transaction.
2. **Client-Side Deduplication Only**:
   - *Rejected*: Ineffective against network timeouts, automated retry scripts, or hostile API abuse.

## Consequences
- **Positive**:
  - Eliminates duplicate money movement from client retries or network drops.
  - Detects and prevents payload tampering or key reuse across different operations.
  - Persists across application restarts and works seamlessly across multi-node deployments.
- **Negative**:
  - Adds one database lookup and insert per mutating financial request.
  - Requires periodic TTL cleanup or partitioning of old idempotency records (e.g., records older than 30 days).

## Trade-offs
We accept a slight persistence overhead on mutating endpoints in exchange for absolute protection against duplicate transaction execution.
