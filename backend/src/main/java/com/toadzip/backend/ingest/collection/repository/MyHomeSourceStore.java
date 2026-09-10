package com.toadzip.backend.ingest.collection.repository;

import com.toadzip.backend.ingest.collection.domain.MyHomeAnnouncementSource;
import com.toadzip.backend.ingest.collection.domain.MyHomeAnnouncementSourceSnapshot;
import com.toadzip.backend.ingest.collection.domain.MyHomeComplexSource;
import com.toadzip.backend.ingest.collection.domain.MyHomeComplexSourceSnapshot;
import com.toadzip.backend.ingest.collection.dto.MyHomeRegion;
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
    public int replaceComplexRegion(MyHomeRegion region, List<MyHomeComplexSourceSnapshot> snapshots) {
        Instant collectedAt = clock.instant();
        validateRegion(region, snapshots);
        Map<String, MyHomeComplexSourceSnapshot> unique = new LinkedHashMap<>();
        for (MyHomeComplexSourceSnapshot snapshot : snapshots) {
            unique.put(MyHomeComplexSource.sourceKeyOf(snapshot), snapshot);
        }
        Map<String, MyHomeComplexSource> stored = complexRepository.findAllBySourceKeyIn(unique.keySet())
                .stream()
                .collect(Collectors.toMap(MyHomeComplexSource::getSourceKey, Function.identity()));
        List<MyHomeComplexSource> sources = new ArrayList<>();
        for (Map.Entry<String, MyHomeComplexSourceSnapshot> entry : unique.entrySet()) {
            String sourceKey = entry.getKey();
            MyHomeComplexSourceSnapshot snapshot = entry.getValue();
            MyHomeComplexSource source = stored.get(sourceKey);
            if (source == null) {
                source = MyHomeComplexSource.from(snapshot);
            }
            source.replaceWith(snapshot);
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
    public int storeAnnouncements(String runId, List<MyHomeAnnouncementSourceSnapshot> snapshots) {
        Instant seenAt = clock.instant();
        Map<String, MyHomeAnnouncementSourceSnapshot> unique = new LinkedHashMap<>();
        for (MyHomeAnnouncementSourceSnapshot snapshot : snapshots) {
            unique.put(MyHomeAnnouncementSource.sourceKeyOf(snapshot), snapshot);
        }
        Map<String, MyHomeAnnouncementSource> stored = announcementRepository.findAllBySourceKeyIn(unique.keySet())
                .stream()
                .collect(Collectors.toMap(MyHomeAnnouncementSource::getSourceKey, Function.identity()));
        List<MyHomeAnnouncementSource> sources = new ArrayList<>();
        int sourceOrder = announcementRepository.findMaxSourceOrder() + 1;
        for (Map.Entry<String, MyHomeAnnouncementSourceSnapshot> entry : unique.entrySet()) {
            String sourceKey = entry.getKey();
            MyHomeAnnouncementSourceSnapshot snapshot = entry.getValue();
            MyHomeAnnouncementSource source = stored.get(sourceKey);
            if (source == null) {
                source = MyHomeAnnouncementSource.from(sourceOrder, snapshot);
            }
            source.replaceWith(snapshot);
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

    private void validateRegion(MyHomeRegion region, List<MyHomeComplexSourceSnapshot> snapshots) {
        boolean containsOtherRegion = snapshots.stream()
                .anyMatch(snapshot -> !region.provinceCode().equals(snapshot.brtcCode())
                        || !region.districtCode().equals(snapshot.signguCode()));
        if (containsOtherRegion) {
            throw new IllegalArgumentException(
                    "지역 스냅샷에 다른 지역의 원천 행이 포함되어 있습니다."
            );
        }
    }
}
