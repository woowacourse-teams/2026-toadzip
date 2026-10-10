package com.toadzip.backend.user.service;

import com.toadzip.backend.admin.dto.AdminPage;
import com.toadzip.backend.user.dto.AdminUserProvider;
import com.toadzip.backend.user.dto.AdminUserSummary;
import com.toadzip.backend.user.exception.AdminUserNotFoundException;
import com.toadzip.backend.user.repository.AdminUserQueryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class AdminUserQueryService {

    private final AdminUserQueryRepository repository;

    public AdminUserQueryService(AdminUserQueryRepository repository) {
        this.repository = repository;
    }

    public AdminPage<AdminUserSummary> search(String keyword, AdminUserProvider provider, int page, int size) {
        return repository.search(keyword, provider, page, size);
    }

    public AdminUserSummary detail(long id) {
        return repository.findById(id).orElseThrow(AdminUserNotFoundException::new);
    }
}
