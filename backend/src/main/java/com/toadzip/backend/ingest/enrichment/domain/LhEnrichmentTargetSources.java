package com.toadzip.backend.ingest.enrichment.domain;

import com.toadzip.backend.ingest.collection.lh.domain.LhProviderPolicy;
import com.toadzip.backend.ingest.collection.myhome.announcement.domain.MyHomeAnnouncementCurrentSources;
import com.toadzip.backend.ingest.collection.myhome.announcement.domain.projection.MyHomeAnnouncementSourceView;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** 마이홈 공고 원천 중 LH 보강 대상을 정한다. 현재 원천이면서 공급기관이 LH인 원천만 보강하고 나머지는 보강에서 뺀다. */
public final class LhEnrichmentTargetSources {

    private LhEnrichmentTargetSources() {
    }

    public static List<MyHomeAnnouncementSourceView> select(List<MyHomeAnnouncementSourceView> sources) {
        return MyHomeAnnouncementCurrentSources.select(sources).stream()
                .filter(source -> LhProviderPolicy.isLh(source.getSuplyInsttNm()))
                .toList();
    }

    public static Set<String> excludedSourceKeys(List<MyHomeAnnouncementSourceView> sources) {
        Set<String> targetKeys = select(sources).stream()
                .map(MyHomeAnnouncementSourceView::getSourceKey).collect(Collectors.toSet());
        return sources.stream().map(MyHomeAnnouncementSourceView::getSourceKey)
                .filter(key -> !targetKeys.contains(key))
                .collect(Collectors.toSet());
    }
}
