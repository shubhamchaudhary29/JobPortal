package com.example.backend.shared.pagination;

import org.springframework.data.domain.Page;

import java.util.List;

public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last,
        List<SortResponse> sort) {

    public static <T> PageResponse<T> from(Page<T> page) {
        List<SortResponse> sorts = page.getSort().stream()
                .map(order -> new SortResponse(order.getProperty(), order.getDirection().name()))
                .toList();
        return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements(),
                page.getTotalPages(), page.isFirst(), page.isLast(), sorts);
    }
}
