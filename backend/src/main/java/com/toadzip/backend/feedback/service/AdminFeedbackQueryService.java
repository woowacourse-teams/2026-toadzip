package com.toadzip.backend.feedback.service;

import com.toadzip.backend.admin.dto.AdminPage;
import com.toadzip.backend.feedback.domain.Feedback;
import com.toadzip.backend.feedback.dto.FeedbackSummary;
import com.toadzip.backend.feedback.repository.FeedbackRepository;
import java.util.List;
import java.util.Locale;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
@PreAuthorize("hasRole('ADMIN')")
public class AdminFeedbackQueryService {

    private final FeedbackRepository repository;

    public AdminFeedbackQueryService(FeedbackRepository repository) {
        this.repository = repository;
    }

    public AdminPage<FeedbackSummary> search(String keyword, int page, int size) {
        String searchPattern = pattern(keyword);
        long total = repository.countMatching(searchPattern);
        int totalPages = Math.toIntExact((total + size - 1) / size);
        List<FeedbackSummary> items = items(searchPattern, page, size, total);
        return new AdminPage<>(items, page, (long) page + 1 < totalPages, total, totalPages);
    }

    private List<FeedbackSummary> items(String searchPattern, int page, int size, long total) {
        if ((long) page * size >= total) {
            return List.of();
        }
        Sort sort = Sort.by(Sort.Direction.DESC, "createdAt", "id");
        List<Feedback> result = repository.search(searchPattern, PageRequest.of(page, size, sort));
        return result.stream().map(FeedbackSummary::from).toList();
    }

    private String pattern(String keyword) {
        return "%" + keyword.toLowerCase(Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }
}
