package com.toadzip.backend.ingest.correction.service;

import com.toadzip.backend.ingest.correction.dto.IngestWorkspacePage;
import com.toadzip.backend.ingest.correction.repository.IngestWorkspaceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class IngestWorkspaceService {
    private final IngestWorkspaceRepository repository;

    @Transactional(readOnly = true)
    public IngestWorkspacePage find(String domain, String status, int page, int size) {
        return repository.find(domain, status, page, size);
    }
}
