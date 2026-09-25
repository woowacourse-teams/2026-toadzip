package com.toadzip.backend.ingest.mapping.service;

import com.toadzip.backend.ingest.collection.domain.MyHomeAnnouncementSource;
import java.util.List;
import java.util.Objects;

public final class MyHomeAnnouncementCurrentSources {

    private MyHomeAnnouncementCurrentSources() {
    }

    public static List<MyHomeAnnouncementSource> select(List<MyHomeAnnouncementSource> sources) {
        List<MyHomeAnnouncementSource> active = sources.stream()
                .filter(MyHomeAnnouncementSource::isActive)
                .toList();
        List<MyHomeAnnouncementSource> candidates = active.isEmpty() ? sources : active;
        MyHomeAnnouncementSource latest = candidates.stream()
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
