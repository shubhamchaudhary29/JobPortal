package com.example.backend.shared.pagination;

import com.example.backend.shared.error.BadRequestException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.Locale;
import java.util.Set;

public final class PageRequestFactory {
    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    private PageRequestFactory() { }

    public static Pageable create(int page, int size, String sort, Set<String> allowed, String defaultProperty) {
        if (page < 0) throw new BadRequestException("Page must be zero or greater");
        if (size < 1 || size > MAX_SIZE) throw new BadRequestException("Size must be between 1 and " + MAX_SIZE);
        String value = sort == null || sort.isBlank() ? defaultProperty + ",desc" : sort.trim();
        String[] parts = value.split(",", -1);
        if (parts.length > 2 || parts[0].isBlank() || !allowed.contains(parts[0]))
            throw new BadRequestException("Unsupported sort field");
        Sort.Direction direction;
        try { direction = parts.length == 1 ? Sort.Direction.ASC : Sort.Direction.valueOf(parts[1].toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException ex) { throw new BadRequestException("Sort direction must be asc or desc"); }
        Sort safeSort = Sort.by(new Sort.Order(direction, parts[0]), new Sort.Order(direction, "id"));
        return PageRequest.of(page, size, safeSort);
    }
}
