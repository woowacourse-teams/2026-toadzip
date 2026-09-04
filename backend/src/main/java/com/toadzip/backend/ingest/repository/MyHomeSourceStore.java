package com.toadzip.backend.ingest.repository;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.toadzip.backend.ingest.domain.MyHomeAnnouncementSource;
import com.toadzip.backend.ingest.domain.MyHomeComplexSource;
import com.toadzip.backend.ingest.dto.MyHomeAnnouncementSourceItem;
import com.toadzip.backend.ingest.dto.MyHomeComplexSourceItem;
import com.toadzip.backend.ingest.dto.MyHomeRegion;

@Repository
public class MyHomeSourceStore {

    private final MyHomeComplexSourceRepository complexRepository;

    private final MyHomeAnnouncementSourceRepository announcementRepository;

    private final Clock clock;

    public MyHomeSourceStore(
            MyHomeComplexSourceRepository complexRepository,
            MyHomeAnnouncementSourceRepository announcementRepository,
            Clock clock
    ) {
        this.complexRepository = complexRepository;
        this.announcementRepository = announcementRepository;
        this.clock = clock;
    }

    @Transactional
    public int replaceComplexRegion(MyHomeRegion region, List<MyHomeComplexSourceItem> items) {
        Instant collectedAt = clock.instant();
        validateRegion(region, items);
        Map<String, MyHomeComplexSourceItem> unique = new LinkedHashMap<>();
        for (MyHomeComplexSourceItem item : items) {
            unique.put(MyHomeComplexSource.sourceKeyOf(item.toSourceData()), item);
        }
        Map<String, MyHomeComplexSource> stored = complexRepository.findAllBySourceKeyIn(unique.keySet())
                .stream()
                .collect(Collectors.toMap(MyHomeComplexSource::getSourceKey, Function.identity()));
        List<MyHomeComplexSource> sources = new ArrayList<>();
        for (Map.Entry<String, MyHomeComplexSourceItem> entry : unique.entrySet()) {
            String sourceKey = entry.getKey();
            MyHomeComplexSourceItem item = entry.getValue();
            MyHomeComplexSource source = stored.get(sourceKey);
            if (source == null) {
                source = MyHomeComplexSource.from(item.toSourceData());
            }
            source.replaceWith(item.toSourceData());
            source.markCollectedAt(collectedAt);
            sources.add(source);
        }
        complexRepository.saveAll(sources);
        List<MyHomeComplexSource> stale = complexRepository
                .findAllByBrtcCodeAndSignguCode(region.provinceCode(), region.districtCode())
                .stream()
                .filter(source -> !unique.containsKey(source.getSourceKey()))
                .toList();
        complexRepository.deleteAll(stale);
        return sources.size();
    }

    @Transactional
    public int storeAnnouncements(String runId, List<MyHomeAnnouncementSourceItem> items) {
        Instant seenAt = clock.instant();
        Map<String, MyHomeAnnouncementSourceItem> unique = new LinkedHashMap<>();
        for (MyHomeAnnouncementSourceItem item : items) {
            unique.put(MyHomeAnnouncementSource.sourceKeyOf(item.toSourceData()), item);
        }
        Map<String, MyHomeAnnouncementSource> stored = announcementRepository.findAllBySourceKeyIn(unique.keySet())
                .stream()
                .collect(Collectors.toMap(MyHomeAnnouncementSource::getSourceKey, Function.identity()));
        List<MyHomeAnnouncementSource> sources = new ArrayList<>();
        int sourceOrder = announcementRepository.findMaxSourceOrder() + 1;
        for (Map.Entry<String, MyHomeAnnouncementSourceItem> entry : unique.entrySet()) {
            String sourceKey = entry.getKey();
            MyHomeAnnouncementSourceItem item = entry.getValue();
            MyHomeAnnouncementSource source = stored.get(sourceKey);
            if (source == null) {
                source = MyHomeAnnouncementSource.from(sourceOrder, item.toSourceData());
            }
            source.replaceWith(item.toSourceData());
            source.markSeen(runId, seenAt);
            sources.add(source);
            sourceOrder++;
        }
        announcementRepository.saveAll(sources);
        return sources.size();
    }

    @Transactional
    public void completeAnnouncementCollection(String runId) {
        List<MyHomeAnnouncementSource> missedSources = announcementRepository
                .findAllActiveNotSeenInRun(runId);
        missedSources.forEach(MyHomeAnnouncementSource::markMissed);
    }

    private void validateRegion(MyHomeRegion region, List<MyHomeComplexSourceItem> items) {
        boolean containsOtherRegion = items.stream()
                .anyMatch(item -> !region.provinceCode().equals(item.brtcCode())
                        || !region.districtCode().equals(item.signguCode()));
        if (containsOtherRegion) {
            throw new IllegalArgumentException(
                    "지역 스냅샷에 다른 지역의 원천 행이 포함되어 있습니다."
            );
        }
    }
}
