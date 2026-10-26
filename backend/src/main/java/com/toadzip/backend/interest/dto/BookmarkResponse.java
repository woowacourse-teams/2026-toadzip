package com.toadzip.backend.interest.dto;

import com.toadzip.backend.interest.domain.BookmarkType;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record BookmarkResponse(List<Item> items) {
    public record Item(BookmarkType type, String targetId, String name, LocalDateTime createdAt,
            boolean available, String address, String status, LocalDate applicationStartDate,
            LocalDate applicationEndDate) {
    }
}
