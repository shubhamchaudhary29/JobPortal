package com.example.backend.shared.pagination;

import com.example.backend.shared.error.BadRequestException;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PageRequestFactoryTest {
    private static final Set<String> SORTS = Set.of("createdAt", "title");

    @Test
    void defaultsBoundsAndAllowlistAreEnforced() {
        var page = PageRequestFactory.create(0, 20, "createdAt,desc", SORTS, "createdAt");
        assertEquals(0, page.getPageNumber());
        assertEquals(20, page.getPageSize());
        assertEquals("DESC", page.getSort().getOrderFor("createdAt").getDirection().name());
        assertEquals("DESC", page.getSort().getOrderFor("id").getDirection().name());
        assertEquals("ASC", PageRequestFactory.create(0, 20, "title,asc", SORTS, "createdAt")
                .getSort().getOrderFor("title").getDirection().name());
        assertThrows(BadRequestException.class, () -> PageRequestFactory.create(-1, 20, null, SORTS, "createdAt"));
        assertThrows(BadRequestException.class, () -> PageRequestFactory.create(0, 0, null, SORTS, "createdAt"));
        assertThrows(BadRequestException.class, () -> PageRequestFactory.create(0, 101, null, SORTS, "createdAt"));
        assertThrows(BadRequestException.class, () -> PageRequestFactory.create(0, 20, "password,asc", SORTS, "createdAt"));
        assertThrows(BadRequestException.class, () -> PageRequestFactory.create(0, 20, "title,sideways", SORTS, "createdAt"));
    }

    @Test
    void metadataRepresentsFirstMiddleLastAndEmptyPages() {
        var pageable = PageRequestFactory.create(1, 2, "title,asc", SORTS, "createdAt");
        PageResponse<String> middle = PageResponse.from(new PageImpl<>(List.of("c", "d"), pageable, 5));
        assertEquals(1, middle.page()); assertFalse(middle.first()); assertFalse(middle.last());
        PageResponse<String> empty = PageResponse.from(new PageImpl<>(List.of(),
                PageRequestFactory.create(0, 20, null, SORTS, "createdAt"), 0));
        assertTrue(empty.first()); assertTrue(empty.last()); assertEquals(0, empty.totalElements());
    }
}
