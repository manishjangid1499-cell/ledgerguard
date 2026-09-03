package com.ledgerguard.common.application;

/**
 * Encapsulates the result of an atomic submission claim transaction.
 *
 * @param <T> operation type (FundingOperation or Payout)
 * @param operation the managed operation entity
 * @param submissionClaimed true if and only if this caller atomically transitioned the entity from CREATED to PROCESSING
 */
public record SubmissionPreparationResult<T>(T operation, boolean submissionClaimed) {
}
