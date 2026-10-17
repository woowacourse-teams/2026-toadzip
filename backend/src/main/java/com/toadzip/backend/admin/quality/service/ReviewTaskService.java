package com.toadzip.backend.admin.quality.service;

import com.toadzip.backend.admin.domain.AdminDataChange;
import com.toadzip.backend.admin.dto.AdminPage;
import com.toadzip.backend.admin.exception.AdminDataConflictException;
import com.toadzip.backend.admin.quality.domain.QualityResource;
import com.toadzip.backend.admin.quality.dto.ReviewTask;
import com.toadzip.backend.admin.quality.dto.SaveReviewTask;
import com.toadzip.backend.admin.quality.repository.ReviewTaskRepository;
import com.toadzip.backend.admin.repository.AdminAccountRepository;
import com.toadzip.backend.admin.repository.AdminDataChangeRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
@Transactional(readOnly = true)
public class ReviewTaskService {
    private final ReviewTaskRepository tasks;
    private final QualityTargetService targets;
    private final AdminAccountRepository accounts;
    private final AdminDataChangeRepository changes;
    private final ObjectMapper json;
    private final Clock clock;

    public ReviewTaskService(ReviewTaskRepository tasks, QualityTargetService targets, AdminAccountRepository accounts,
            AdminDataChangeRepository changes, ObjectMapper json, Clock clock) {
        this.tasks = tasks;
        this.targets = targets;
        this.accounts = accounts;
        this.changes = changes;
        this.json = json;
        this.clock = clock;
    }

    public List<String> assignees() {
        return tasks.assignees();
    }

    public ReviewTask find(QualityResource resource, long id) {
        targets.target(targets.detail(resource, id), "basic");
        return tasks.find(resource, id, today()).orElse(null);
    }

    public AdminPage<ReviewTask> search(String actor, boolean mine, String state, boolean overdue,
            boolean alerts, int page, int size) {
        return tasks.search(actor, mine, state, overdue, alerts, page, size, today());
    }

    @Transactional
    public ReviewTask save(QualityResource resource, long id, SaveReviewTask request, String actor) {
        var before = find(resource, id);
        if (!request.assignee().isEmpty() && accounts.findByLoginIdentifier(request.assignee()).isEmpty()) {
            throw new IllegalArgumentException("실제 관리자 계정을 담당자로 선택해 주세요.");
        }
        if (request.dueDate() != null && (request.dueDate().getYear() < 1 || request.dueDate().getYear() > 9999)) {
            throw new IllegalArgumentException("기한은 YYYY-MM-DD 날짜로 입력해 주세요.");
        }
        if (!tasks.save(resource, id, request, actor)) {
            throw new AdminDataConflictException("다른 관리자가 업무를 변경했습니다. 작성한 내용을 보관하고 최신 업무를 조회하세요.");
        }
        var after = tasks.find(resource, id, today()).orElseThrow();
        changes.save(new AdminDataChange(resource.name(), id, "UPDATE_REVIEW_TASK", actor,
                json.writeValueAsString(before), json.writeValueAsString(after)));
        return after;
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), ZoneId.of("Asia/Seoul"));
    }
}
