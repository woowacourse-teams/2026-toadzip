package com.toadzip.backend.ingest.collection.lh.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.toadzip.backend.ingest.collection.lh.dto.LhAnnouncementRequest;
import com.toadzip.backend.ingest.collection.lh.service.LhAnnouncementCollectionCandidateResolver.Candidate;
import com.toadzip.backend.ingest.collection.lh.service.LhAnnouncementCollectionCandidateResolver.Resolution;
import com.toadzip.backend.ingest.collection.lh.service.LhAnnouncementCollectionCandidateResolver.Skipped;
import com.toadzip.backend.ingest.collection.lh.service.LhAnnouncementCollectionCandidateResolver.SourceResolution;
import com.toadzip.backend.ingest.collection.myhome.announcement.domain.projection.MyHomeAnnouncementSourceView;
import java.time.Clock;
import java.time.Instant;
import java.time.Period;
import java.time.ZoneOffset;
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

        var selection = selector.select(withoutSources(resolutions), handled, true);

        assertThat(selection.conflicts()).containsExactly(conflictFirst);
        assertThat(selection.candidates()).extracting(Candidate::sourceAnnouncementKey).containsExactly("B");
        assertThat(selection.duplicateCount()).isEqualTo(1);
        assertThat(handled).containsExactlyInAnyOrder("A", "B", "C");
        assertThat(selection.skipped()).extracting(Skipped::sourceAnnouncementKey).containsExactly("D");
        assertThat(selection.sourceKeysWithoutEligibleCandidates()).containsExactly("D");
    }

    @Test
    void 정기_수집은_후보마다_그_원천의_접수_마감일로_수집_정책을_판단한다() {
        Candidate recent = candidate("A", "PAN-1");
        Candidate ended = candidate("B", "PAN-2");
        var resolutions = List.of(
                new SourceResolution(sourceEndingOn("20260920"), recent),
                new SourceResolution(sourceEndingOn("20260801"), ended));

        var selection = selector.select(resolutions, new HashSet<>(), false);

        assertThat(selection.candidates()).containsExactly(recent);
        assertThat(selection.policyExcludedCount()).isOne();
    }

    private MyHomeAnnouncementSourceView sourceEndingOn(String endDe) {
        MyHomeAnnouncementSourceView source = mock(MyHomeAnnouncementSourceView.class);
        when(source.getEndDe()).thenReturn(endDe);
        return source;
    }

    private Candidate candidate(String sourceKey, String panId) {
        return new Candidate(sourceKey, "source " + sourceKey,
                new LhAnnouncementRequest(panId, "03", "06", "07", "062"));
    }

    private List<SourceResolution> withoutSources(List<Resolution> resolutions) {
        // 강제 수집은 수집 정책을 보지 않으므로 원천 행 내용은 쓰이지 않는다.
        return resolutions.stream().map(resolution -> new SourceResolution(null, resolution)).toList();
    }
}
