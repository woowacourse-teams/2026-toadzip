package com.toadzip.backend.ingest.collection.myhome.announcement.domain;

import com.toadzip.backend.ingest.collection.myhome.announcement.domain.projection.MyHomeAnnouncementSourceView;
import java.util.List;
import java.util.Objects;

public final class MyHomeAnnouncementCurrentSources {

    private MyHomeAnnouncementCurrentSources() {
    }

    public static List<MyHomeAnnouncementSourceView> select(List<MyHomeAnnouncementSourceView> sources) {
        List<MyHomeAnnouncementSourceView> active = sources.stream()
                .filter(MyHomeAnnouncementSourceView::isActive)
                .toList();
        List<MyHomeAnnouncementSourceView> candidates = sources;
        if (!active.isEmpty()) {
            candidates = active;
        }
        MyHomeAnnouncementSourceView latest = candidates.stream()
                .filter(source -> source.getLastSeenRunId() != null && source.getCollectedAt() != null)
                .max((left, right) -> left.getCollectedAt().compareTo(right.getCollectedAt()))
                .orElse(null);
        if (latest == null) {
            return candidates;
        }
        boolean ambiguousLatestRun = candidates.stream()
                .filter(source -> Objects.equals(source.getCollectedAt(), latest.getCollectedAt()))
                .anyMatch(source -> !Objects.equals(source.getLastSeenRunId(), latest.getLastSeenRunId()));
        if (ambiguousLatestRun) {
            return candidates;
        }
        return candidates.stream()
                .filter(source -> Objects.equals(source.getLastSeenRunId(), latest.getLastSeenRunId()))
                .toList();
    }
}
