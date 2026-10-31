package com.toadzip.backend.ingest.mapping.service;

import static com.toadzip.backend.ingest.failure.domain.IngestFailureStatus.PENDING;

import com.toadzip.backend.ingest.collection.myhome.announcement.domain.projection.MyHomeAnnouncementSource;
import com.toadzip.backend.ingest.enrichment.service.LhEnrichmentMappingState;
import com.toadzip.backend.ingest.mapping.repository.MyHomeAnnouncementMappingFailureRepository;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class MyHomeLhEnrichmentMappingState implements LhEnrichmentMappingState {

    private final MyHomeAnnouncementMappingFailureRepository failureRepository;
    private final MyHomeAnnouncementCommonValuesMapper commonValuesMapper;

    @Override
    public Set<String> incompleteAnnouncementIdentifiers() {
        return failureRepository.findAllByStatus(PENDING).stream()
                .map(failure -> failure.getSourceAnnouncementIdentifier())
                .filter(identifier -> identifier != null && !identifier.isBlank())
                .collect(Collectors.toSet());
    }

    @Override
    public String commonValuesRejection(List<MyHomeAnnouncementSource> sources) {
        return commonValuesMapper.rejectionDetail(sources);
    }
}
