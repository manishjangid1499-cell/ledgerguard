package com.ledgerguard.lab.api.dto;

import com.ledgerguard.lab.model.ScenarioId;

import java.util.List;

public record ScenarioMetadataView(
        ScenarioId id,
        String name,
        String description,
        String category,
        List<String> keyInvariants,
        List<String> faultMechanisms
) {}
