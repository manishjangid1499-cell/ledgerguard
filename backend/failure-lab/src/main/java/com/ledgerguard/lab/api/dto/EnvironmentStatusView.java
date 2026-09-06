package com.ledgerguard.lab.api.dto;

public record EnvironmentStatusView(
        String status,
        String postgresStatus,
        String kafkaStatus,
        String pspAdapterStatus,
        String mode,
        String safeNotice
) {}
