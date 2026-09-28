package com.toadzip.backend.admin.dto;

import java.util.List;

public record AdminPage<T>(List<T> items, int page, boolean hasNext, long totalElements, int totalPages) {
}
