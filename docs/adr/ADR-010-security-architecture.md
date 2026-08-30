# ADR-010: Security Architecture & Authoritative In-Monolith Auth

## Status
Accepted

## Context
Securing a financial platform requires robust authentication, strict role-based access control (RBAC), fine-grained resource ownership verification, credential protection, and defense against automated attacks.

A common industry anti-pattern is deploying an external identity microservice (such as Keycloak or a separate Auth service) early in a project's lifecycle. This introduces network hops, cross-service token synchronization latency, operational fragility, and complex distributed session management without solving the core need for domain-level authorization inside the financial monolith.

## Decision
We implement **Authoritative In-Monolith Security within `ledgerguard-api`**:

1. **Integrated Spring Security & JWT**:
   - Authentication, password hashing (BCrypt), JWT token signing/verification, and refresh-token rotation are fully contained within `ledgerguard-api`.
   - No separate identity microservice is deployed.
2. **Authoritative Backend Access Control**:
   - The backend API is the single authority for authorization. Frontend role-based rendering is treated purely as UI enhancement, never as a security boundary.
   - Resource access enforces strict ownership rules: a `CUSTOMER` can only view and manipulate accounts they own.
3. **Defense-in-Depth Mechanisms**:
   - **Access / Refresh Tokens**: Short-lived JWTs (15 min) paired with database-backed revocable refresh tokens.
   - **Webhook Security**: Cryptographic HMAC-SHA256 signature verification and replay prevention windows on all incoming PSP callbacks.
   - **Rate Limiting & Throttling**: Token-bucket rate limiting on authentication and transaction endpoints.
   - **PII & Credential Scrubbing**: Strict logging policy forbidding passwords, cardholder data, or secret keys from log outputs.

## Alternatives Considered
1. **External Keycloak / OAuth2 Server**:
   - *Rejected*: Massive resource footprint, unnecessary deployment complexity, and slow startup times for local and CI environments without adding security value over a well-implemented in-monolith Spring Security solution.
2. **Third-Party SaaS Auth (Auth0 / Firebase)**:
   - *Rejected*: Introduces external cloud dependencies and network boundaries for a self-contained portfolio/open-source codebase.

## Consequences
- **Positive**:
  - Fast, zero-network-hop authentication and authorization checks.
  - Tightly coupled domain ownership validations (e.g., verifying `user.id == wallet.owner_id` within JPA repository queries).
  - Simple, unified deployment and local development setup.
- **Negative**:
  - Monolith handles authentication credential storage (mitigated by BCrypt work factors and standard Spring Security best practices).

## Trade-offs
We trade third-party single-sign-on delegation for a lightweight, performant, self-contained in-monolith security implementation with zero external infrastructure bloat.
