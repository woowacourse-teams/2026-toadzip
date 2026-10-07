package com.toadzip.backend.feedback.service;

import com.toadzip.backend.admin.dto.AdminPage;
import com.toadzip.backend.feedback.domain.Feedback;
import com.toadzip.backend.feedback.dto.FeedbackSummary;
import com.toadzip.backend.feedback.repository.FeedbackRepository;
import java.util.List;
import java.util.Locale;
import org.springframework.data.domain.Page;
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
        Sort sort = Sort.by(Sort.Direction.DESC, "createdAt", "id");
        Page<Feedback> result = repository.search(pattern(keyword), PageRequest.of(page, size, sort));
        List<FeedbackSummary> items = result.stream().map(FeedbackSummary::from).toList();
        return new AdminPage<>(items, page, result.hasNext(), result.getTotalElements(), result.getTotalPages());
    }

    private String pattern(String keyword) {
        return "%" + keyword.toLowerCase(Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }
}
