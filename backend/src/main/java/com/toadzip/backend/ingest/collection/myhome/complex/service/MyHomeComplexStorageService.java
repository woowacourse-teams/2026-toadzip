package com.toadzip.backend.ingest.collection.myhome.complex.service;

import com.toadzip.backend.ingest.collection.history.domain.SourceCollectionRecord;
import com.toadzip.backend.ingest.collection.history.service.SourceCollectionRecordService;
import com.toadzip.backend.ingest.collection.myhome.complex.domain.MyHomeComplexRegionSource;
import com.toadzip.backend.ingest.collection.myhome.complex.domain.MyHomeComplexSource;
import com.toadzip.backend.ingest.collection.myhome.complex.domain.MyHomeComplexSourceSnapshot;
import com.toadzip.backend.ingest.collection.myhome.complex.dto.MyHomeComplexCollectedResponse;
import com.toadzip.backend.ingest.collection.myhome.complex.dto.MyHomeComplexCollectionRequest;
import com.toadzip.backend.ingest.collection.myhome.complex.repository.MyHomeComplexCollectionRepository;
import com.toadzip.backend.ingest.collection.myhome.complex.repository.MyHomeComplexRegionSourceRepository;
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
public class MyHomeComplexStorageService {

    private final MyHomeComplexCollectionRepository sourceRepository;
    private final MyHomeComplexRegionSourceRepository regionRepository;
    private final SourceCollectionRecordService records;
    private final IngestWriteOwnershipGuard ownershipGuard;
    private final Clock clock;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(
            UUID recordId, MyHomeComplexCollectionRequest request, MyHomeComplexCollectedResponse response
    ) {
        ownershipGuard.verifyWrite();
        SourceCollectionRecord record = records.requireRunning(recordId, request);
        response.validateFor(request);
        if (response.collectedAt().isAfter(clock.instant())) {
            throw new IllegalArgumentException("실제 수집 시각은 저장 실행 시각보다 늦을 수 없습니다.");
        }
        MyHomeComplexRegionSource region = regionRepository.findByProvinceCodeAndDistrictCode(
                request.provinceCode(), request.districtCode())
                .orElseGet(() -> MyHomeComplexRegionSource.create(request.provinceCode(), request.districtCode()));
        region.replaceSnapshot(response.collectedAt(), record);
        // 빈 지역도 최신성·버전을 보존한다. 지역 전체 교체 경쟁은 이 버전 또는 지역 UNIQUE에서 거절한다.
        regionRepository.saveAndFlush(region);
        replaceRegion(region, response);
        records.complete(recordId, request, response.rows().size());
    }

    private void replaceRegion(MyHomeComplexRegionSource region, MyHomeComplexCollectedResponse response) {
        Map<Long, List<MyHomeComplexSourceSnapshot>> grouped = response.rows().stream().collect(
                Collectors.groupingBy(MyHomeComplexSourceSnapshot::hsmpSn, LinkedHashMap::new, Collectors.toList()));
        Map<Long, MyHomeComplexSource> sources = sourceRepository.findAllByHsmpSnIn(grouped.keySet())
                .stream().collect(Collectors.toMap(MyHomeComplexSource::getHsmpSn, Function.identity()));
        sources.values().forEach(source -> verifyRegion(source, region));
        List<MyHomeComplexSource> removed = sourceRepository.findAllByRegion_ProvinceCodeAndRegion_DistrictCode(
                region.getProvinceCode(), region.getDistrictCode()).stream()
                .filter(source -> !grouped.containsKey(source.getHsmpSn())).toList();
        sourceRepository.deleteAll(removed);
        grouped.keySet().forEach(id -> sources.computeIfAbsent(id, key -> MyHomeComplexSource.create(key, region)));
        sources.values().forEach(MyHomeComplexSource::clearResponseRows);
        // 동일한 응답 순번을 다시 넣기 전에 삭제를 flush한다. 실패하면 지역 교체 전체가 롤백된다.
        sourceRepository.saveAllAndFlush(sources.values());
        grouped.forEach((id, rows) -> sources.get(id).addResponseRows(rows));
        sourceRepository.flush();
    }

    private void verifyRegion(MyHomeComplexSource source, MyHomeComplexRegionSource region) {
        if (!source.getRegion().matches(region.getProvinceCode(), region.getDistrictCode())) {
            throw new IllegalArgumentException("기존 단지 식별자의 지역과 충돌하여 해당 지역 반영을 보류합니다.");
        }
    }
}
