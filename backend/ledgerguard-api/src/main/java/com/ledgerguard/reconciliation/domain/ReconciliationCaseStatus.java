package com.ledgerguard.reconciliation.domain;

/**
 * Lifecycle status of a reconciliation review case.
 * <p>
 * State transitions:
 * <ul>
 *   <li>{@code OPEN} &rarr; {@code IN_REVIEW} (claimed by an operator)</li>
 *   <li>{@code OPEN} &rarr; {@code RESOLVED} (direct resolution)</li>
 *   <li>{@code IN_REVIEW} &rarr; {@code RESOLVED} (resolution after claim)</li>
 * </ul>
 * {@code RESOLVED} is terminal and permanently immutable.
 */
public enum ReconciliationCaseStatus {
    OPEN,
    IN_REVIEW,
    RESOLVED
}
