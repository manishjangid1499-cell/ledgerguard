package com.ledgerguard.shared.api;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

public final class QueryPagination {
    private QueryPagination() {}

    public static PageRequest newestFirst(int page, int size) {
        return PageRequest.of(Math.max(0, page), size <= 0 ? 20 : Math.min(size, 50),
                Sort.by(Sort.Direction.DESC, "createdAt", "id"));
    }

    public static <T> PagedResponse<T> response(Page<T> page) {
        return new PagedResponse<>(page.getContent(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }
}
