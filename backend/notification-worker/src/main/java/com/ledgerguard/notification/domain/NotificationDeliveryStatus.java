package com.ledgerguard.notification.domain;

/**
 * Lifecycle states for notification delivery tracking.
 */
public enum NotificationDeliveryStatus {
    PENDING,
    SENDING,
    RETRY_PENDING,
    SENT,
    FAILED,
    SKIPPED,
    LEGACY_RECORDED
}
