package com.toadzip.backend.ingest.repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.toadzip.backend.ingest.domain.MyHomeComplexSource;
import com.toadzip.backend.ingest.domain.MyHomeNoticeSource;
import com.toadzip.backend.ingest.dto.MyHomeComplexSourceItem;
import com.toadzip.backend.ingest.dto.MyHomeNoticeSourceItem;
import com.toadzip.backend.ingest.dto.MyHomeRegion;

@Repository
public class MyHomeSourceStore {

    private final MyHomeComplexSourceRepository complexRepository;

    private final MyHomeNoticeSourceRepository noticeRepository;

    public MyHomeSourceStore(
            MyHomeComplexSourceRepository complexRepository,
            MyHomeNoticeSourceRepository noticeRepository
    ) {
        this.complexRepository = complexRepository;
        this.noticeRepository = noticeRepository;
    }

    @Transactional
    public int replaceComplexRegion(MyHomeRegion region, List<MyHomeComplexSourceItem> items) {
        validateRegion(region, items);
        Set<String> sourceKeys = items.stream()
                .map(MyHomeComplexSource::sourceKeyOf)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, MyHomeComplexSource> stored = complexRepository.findAllBySourceKeyIn(sourceKeys)
                .stream()
                .collect(Collectors.toMap(MyHomeComplexSource::getSourceKey, Function.identity()));
        List<MyHomeComplexSource> sources = new ArrayList<>();
        for (MyHomeComplexSourceItem item : items) {
            String sourceKey = MyHomeComplexSource.sourceKeyOf(item);
            MyHomeComplexSource source = stored.get(sourceKey);
            if (source == null) {
                source = MyHomeComplexSource.from(item);
            }
            source.replaceWith(item);
            sources.add(source);
        }
        complexRepository.saveAll(sources);
        List<MyHomeComplexSource> stale = complexRepository
                .findAllByBrtcCodeAndSignguCode(region.provinceCode(), region.districtCode())
                .stream()
                .filter(source -> !sourceKeys.contains(source.getSourceKey()))
                .toList();
        complexRepository.deleteAll(stale);
        return items.size();
    }

    @Transactional
    public int storeNotices(List<MyHomeNoticeSourceItem> items) {
        Map<String, MyHomeNoticeSourceItem> unique = new LinkedHashMap<>();
        for (MyHomeNoticeSourceItem item : items) {
            unique.put(MyHomeNoticeSource.sourceKeyOf(item), item);
        }
        Map<String, MyHomeNoticeSource> stored = noticeRepository.findAllBySourceKeyIn(unique.keySet())
                .stream()
                .collect(Collectors.toMap(MyHomeNoticeSource::getSourceKey, Function.identity()));
        List<MyHomeNoticeSource> sources = new ArrayList<>();
        int sourceOrder = noticeRepository.findMaxSourceOrder() + 1;
        for (Map.Entry<String, MyHomeNoticeSourceItem> entry : unique.entrySet()) {
            String sourceKey = entry.getKey();
            MyHomeNoticeSourceItem item = entry.getValue();
            MyHomeNoticeSource source = stored.get(sourceKey);
            if (source == null) {
                source = MyHomeNoticeSource.from(sourceOrder, item);
            }
            source.replaceWith(item);
            sources.add(source);
            sourceOrder++;
        }
        noticeRepository.saveAll(sources);
        return sources.size();
    }

    private void validateRegion(MyHomeRegion region, List<MyHomeComplexSourceItem> items) {
        boolean containsOtherRegion = items.stream()
                .anyMatch(item -> !region.provinceCode().equals(item.brtcCode())
                        || !region.districtCode().equals(item.signguCode()));
        if (containsOtherRegion) {
            throw new IllegalArgumentException("지역 스냅샷에 다른 지역의 원천 행이 포함되어 있습니다.");
        }
    }
}
