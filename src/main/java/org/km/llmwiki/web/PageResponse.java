package org.km.llmwiki.web;

import java.util.List;

public record PageResponse<T>(T data, PageMeta page) {

    public static <T> PageResponse<List<T>> of(List<T> items, int number, int size, long totalElements) {
        int totalPages = size == 0 ? 0 : (int) Math.ceil((double) totalElements / size);
        return new PageResponse<>(items, new PageMeta(number, size, totalElements, totalPages));
    }

    public record PageMeta(int number, int size, long totalElements, int totalPages) {
    }
}
