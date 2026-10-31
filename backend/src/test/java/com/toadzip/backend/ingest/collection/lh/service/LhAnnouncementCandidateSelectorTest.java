package com.toadzip.backend.ingest.collection.lh.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.toadzip.backend.ingest.collection.lh.dto.LhAnnouncementRequest;
import com.toadzip.backend.ingest.collection.lh.service.LhAnnouncementCollectionCandidateResolver.Candidate;
import com.toadzip.backend.ingest.collection.lh.service.LhAnnouncementCollectionCandidateResolver.Resolution;
import com.toadzip.backend.ingest.collection.lh.service.LhAnnouncementCollectionCandidateResolver.Skipped;
import com.toadzip.backend.ingest.collection.myhome.announcement.domain.projection.MyHomeAnnouncementSource;
import java.time.Clock;
import java.time.Instant;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class LhAnnouncementCandidateSelectorTest {

    private final LhAnnouncementCandidateSelector selector = new LhAnnouncementCandidateSelector(
            new LhAnnouncementCollectionPolicy(Clock.fixed(Instant.parse("2026-10-01T00:00:00Z"), ZoneOffset.UTC),
                    Period.ofDays(30)));

    @Test
    void 요청이_서로_다른_공고는_충돌로_한_번만_빼고_앞_배치에서_다룬_공고는_중복으로_뺀다() {
        Candidate conflictFirst = candidate("A", "PAN-1");
        List<Resolution> resolutions = List.of(
                conflictFirst, candidate("A", "PAN-2"), candidate("B", "PAN-3"), candidate("C", "PAN-4"),
                new Skipped("D", "source D", "지원하지 않는 조건"));
        Set<String> handled = new HashSet<>(Set.of("C"));

        var selection = selector.select(sources(resolutions.size()), resolutions, handled, true);

        assertThat(selection.conflicts()).containsExactly(conflictFirst);
        assertThat(selection.candidates()).extracting(Candidate::sourceAnnouncementKey).containsExactly("B");
        assertThat(selection.duplicateCount()).isEqualTo(1);
        assertThat(handled).containsExactlyInAnyOrder("A", "B", "C");
        assertThat(selection.skipped()).extracting(Skipped::sourceAnnouncementKey).containsExactly("D");
        assertThat(selection.sourceKeysWithoutEligibleCandidates()).containsExactly("D");
    }

    private Candidate candidate(String sourceKey, String panId) {
        return new Candidate(sourceKey, "source " + sourceKey,
                new LhAnnouncementRequest(panId, "03", "06", "07", "062"));
    }

    private List<MyHomeAnnouncementSource> sources(int size) {
        // 강제 수집은 수집 정책을 보지 않으므로 원천 행 내용은 쓰이지 않는다.
        return Collections.nCopies(size, null);
    }
}
