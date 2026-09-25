package com.ledgerguard.shared.api;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

public final class QueryPagination {
    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 50;

    private QueryPagination() {}

    public static PageRequest newestFirst(int page, int size) {
        return PageRequest.of(Math.max(0, page), size <= 0 ? DEFAULT_SIZE : Math.min(size, MAX_SIZE),
                Sort.by(Sort.Direction.DESC, "createdAt", "id"));
    }

    public static PageRequest oldestFirst(int page, int size) {
        return PageRequest.of(Math.max(0, page), size <= 0 ? DEFAULT_SIZE : Math.min(size, MAX_SIZE),
                Sort.by(Sort.Direction.ASC, "createdAt", "id"));
    }

    public static PageRequest of(int page, int size, String sortDirection) {
        return "oldest".equalsIgnoreCase(sortDirection) ? oldestFirst(page, size) : newestFirst(page, size);
    }

    public static <T> PagedResponse<T> response(Page<T> page) {
        return new PagedResponse<>(page.getContent(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }
}
