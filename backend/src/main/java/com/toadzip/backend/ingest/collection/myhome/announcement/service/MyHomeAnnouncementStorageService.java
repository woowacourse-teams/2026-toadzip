package com.toadzip.backend.ingest.collection.myhome.announcement.service;

import com.toadzip.backend.ingest.collection.history.domain.SourceCollectionRecord;
import com.toadzip.backend.ingest.collection.history.service.SourceCollectionRecordService;
import com.toadzip.backend.ingest.collection.myhome.announcement.domain.MyHomeAnnouncementSource;
import com.toadzip.backend.ingest.collection.myhome.announcement.domain.MyHomeAnnouncementSourceSnapshot;
import com.toadzip.backend.ingest.collection.myhome.announcement.dto.MyHomeAnnouncementCollectedResponse;
import com.toadzip.backend.ingest.collection.myhome.announcement.dto.MyHomeAnnouncementCollectionRequest;
import com.toadzip.backend.ingest.collection.myhome.announcement.repository.MyHomeAnnouncementCollectionRepository;
import com.toadzip.backend.ingest.pipeline.service.IngestWriteOwnershipGuard;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MyHomeAnnouncementStorageService {

    private final MyHomeAnnouncementCollectionRepository sourceRepository;
    private final SourceCollectionRecordService records;
    private final IngestWriteOwnershipGuard ownershipGuard;
    private final Clock clock;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(
            UUID recordId, MyHomeAnnouncementCollectionRequest request, MyHomeAnnouncementCollectedResponse response
    ) {
        ownershipGuard.verifyWrite();
        SourceCollectionRecord record = records.requireRunning(recordId, request);
        response.validateFor(request);
        if (response.collectedAt().isAfter(clock.instant())) {
            throw new IllegalArgumentException("실제 수집 시각은 저장 실행 시각보다 늦을 수 없습니다.");
        }
        storeSources(request, response, record);
        records.complete(recordId, request, response.rows().size());
    }

    private void storeSources(
            MyHomeAnnouncementCollectionRequest request, MyHomeAnnouncementCollectedResponse response,
            SourceCollectionRecord record
    ) {
        Map<String, List<MyHomeAnnouncementSourceSnapshot>> grouped = response.rows().stream().collect(
                Collectors.groupingBy(row -> row.pblancId().strip(), LinkedHashMap::new, Collectors.toList()));
        Map<String, MyHomeAnnouncementSource> sources = sourceRepository.findAllByPblancIdIn(grouped.keySet())
                .stream().collect(Collectors.toMap(MyHomeAnnouncementSource::getPblancId, Function.identity()));
        grouped.keySet().forEach(id -> sources.computeIfAbsent(id,
                key -> MyHomeAnnouncementSource.create(key, record)));
        grouped.forEach((id, rows) -> sources.get(id).beginReplacement(
                request.supplyTypeCode(), response.collectedAt(), record, rows));
        // 조회된 주택의 기존 행을 먼저 삭제한다. 미조회 행은 보존하며 두 flush는 같은 트랜잭션이다.
        sourceRepository.saveAllAndFlush(sources.values());
        grouped.forEach((id, rows) -> sources.get(id).addResponseRows(
                request.supplyTypeCode(), response.collectedAt(), record, rows));
        sourceRepository.flush();
    }
}
