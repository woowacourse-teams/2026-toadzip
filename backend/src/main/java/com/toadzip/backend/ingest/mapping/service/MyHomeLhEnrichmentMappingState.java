package com.toadzip.backend.ingest.mapping.service;

import com.toadzip.backend.ingest.collection.myhome.announcement.domain.projection.MyHomeAnnouncementSourceView;
import com.toadzip.backend.ingest.enrichment.service.LhEnrichmentMappingState;
import com.toadzip.backend.ingest.mapping.repository.MyHomeAnnouncementMappingFailureStore;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class MyHomeLhEnrichmentMappingState implements LhEnrichmentMappingState {

    private final MyHomeAnnouncementMappingFailureStore failureStore;
    private final MyHomeAnnouncementCommonValuesMapper commonValuesMapper;

    @Override
    public Set<String> incompleteAnnouncementIdentifiers() {
        return failureStore.pending().stream()
                .map(failure -> failure.getSourceAnnouncementIdentifier())
                .filter(identifier -> identifier != null && !identifier.isBlank())
                .collect(Collectors.toSet());
    }

    @Override
    public String commonValuesRejection(List<MyHomeAnnouncementSourceView> sources) {
        return commonValuesMapper.rejectionDetail(sources);
    }
}
