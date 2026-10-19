package com.toadzip.backend.admin.quality.service;

import com.toadzip.backend.admin.dto.AdminPage;
import com.toadzip.backend.admin.quality.dto.QualityQueueItem;
import com.toadzip.backend.admin.quality.repository.QualityQueueRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class QualityQueueService {
    private final QualityQueueRepository queue;

    public QualityQueueService(QualityQueueRepository queue) {
        this.queue = queue;
    }

    public AdminPage<QualityQueueItem> search(String resource, String reason, String keyword, int page, int size) {
        return queue.search(resource, reason, keyword.strip(), page, size);
    }
}
