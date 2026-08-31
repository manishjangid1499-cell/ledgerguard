package com.ledgerguard.shared.api;

import java.util.List;

/**
 * Standardized pagination wrapper for API responses.
 *
 * @param <T> item type
 */
public record PagedResponse<T>(
        List<T> items,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
}
