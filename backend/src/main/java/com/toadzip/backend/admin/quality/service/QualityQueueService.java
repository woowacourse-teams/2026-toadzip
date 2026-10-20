package com.toadzip.backend.admin.quality.service;

import com.toadzip.backend.admin.dto.AdminPage;
import com.toadzip.backend.admin.quality.dto.QualityQueueItem;
import com.toadzip.backend.admin.quality.dto.QualityQueueFilter;
import com.toadzip.backend.admin.quality.repository.QualityQueueRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.time.LocalDate;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class QualityQueueService {
    private final QualityQueueRepository queue;
    private final Clock clock;

    public QualityQueueService(QualityQueueRepository queue, Clock clock) {
        this.queue = queue;
        this.clock = clock;
    }

    public AdminPage<QualityQueueItem> search(QualityQueueFilter filter, int page, int size) {
        return queue.search(filter, LocalDate.now(clock), page, size);
    }
}
