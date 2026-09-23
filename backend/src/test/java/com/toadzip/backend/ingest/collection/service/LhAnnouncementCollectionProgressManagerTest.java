package com.toadzip.backend.ingest.collection.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.collection.dto.LhAnnouncementRequest;
import com.toadzip.backend.ingest.collection.repository.LhAnnouncementCollectionProgressStore;
import com.toadzip.backend.ingest.collection.repository.LhAnnouncementCollectionProgressStore.BatchProgress;
import com.toadzip.backend.ingest.collection.service.LhAnnouncementCollectionCandidateResolver.Candidate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LhAnnouncementCollectionProgressManagerTest {

    private static final Instant NOW = Instant.parse("2026-09-19T00:00:00Z");
    private static final Duration REFRESH_TTL = Duration.ofHours(6);

    @Mock
    private LhAnnouncementCollectionProgressStore progressStore;

    @Mock
    private ExternalDataFailureRecorder failureRecorder;

    private LhAnnouncementCollectionProgressManager progressManager;

    @BeforeEach
    void setUp() {
        progressManager = new LhAnnouncementCollectionProgressManager(
                progressStore,
                failureRecorder,
                Clock.fixed(NOW, ZoneOffset.UTC),
                REFRESH_TTL
        );
    }

    @Test
    void 후보의_요청과_공고별_연결로_배치_진행_상태를_조회한다() {
        Candidate candidate = candidate();
        BatchProgress expected = BatchProgress.empty();
        when(progressStore.findBatch(
                ExternalDataSource.LH_ANNOUNCEMENT_DETAIL,
                List.of(candidate.requestDescription()),
                List.of(candidate.sourceAnnouncementKey()),
                NOW.minus(REFRESH_TTL)
        )).thenReturn(expected);

        BatchProgress result = progressManager.findBatch(
                ExternalDataSource.LH_ANNOUNCEMENT_DETAIL,
                List.of(candidate)
        );

        assertThat(result).isSameAs(expected);
    }

    @Test
    void 재수집_만료_시간은_0보다_커야_한다() {
        assertThatThrownBy(() -> new LhAnnouncementCollectionProgressManager(
                progressStore,
                failureRecorder,
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ZERO
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("LH 공고 재수집 만료 시간은 0보다 커야 합니다.");
    }

    @Test
    void 수집_완료_시_실패_이력을_정리하고_체크포인트를_기록한다() {
        Candidate candidate = candidate();

        progressManager.complete(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL, candidate);

        verify(failureRecorder).resolve(
                ExternalDataSource.LH_ANNOUNCEMENT_DETAIL,
                candidate.requestDescription()
        );
        verify(failureRecorder).resolve(
                ExternalDataSource.LH_ANNOUNCEMENT_DETAIL,
                candidate.sourceDescription()
        );
        verify(progressStore).complete(
                ExternalDataSource.LH_ANNOUNCEMENT_DETAIL,
                candidate.sourceAnnouncementKey(),
                candidate.requestDescription(),
                candidate.panId()
        );
    }

    @Test
    void 신선한_요청을_공고에_연결할_때는_체크포인트를_갱신하지_않는다() {
        Candidate candidate = candidate();

        progressManager.link(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL, candidate);

        verify(progressStore).link(
                ExternalDataSource.LH_ANNOUNCEMENT_DETAIL,
                candidate.sourceAnnouncementKey(),
                candidate.requestDescription(),
                candidate.panId()
        );
        verify(progressStore, never()).complete(
                ExternalDataSource.LH_ANNOUNCEMENT_DETAIL,
                candidate.sourceAnnouncementKey(),
                candidate.requestDescription(),
                candidate.panId()
        );
        verify(failureRecorder).resolve(
                ExternalDataSource.LH_ANNOUNCEMENT_DETAIL,
                candidate.requestDescription()
        );
        verify(failureRecorder).resolve(
                ExternalDataSource.LH_ANNOUNCEMENT_DETAIL,
                candidate.sourceDescription()
        );
    }

    @Test
    void 체크포인트_기록이_실패하면_실패_이력을_해소하지_않는다() {
        Candidate candidate = candidate();
        doThrow(new IllegalStateException("체크포인트 기록 실패")).when(progressStore).complete(
                ExternalDataSource.LH_ANNOUNCEMENT_DETAIL,
                candidate.sourceAnnouncementKey(),
                candidate.requestDescription(),
                candidate.panId()
        );

        assertThatThrownBy(() -> progressManager.complete(
                ExternalDataSource.LH_ANNOUNCEMENT_DETAIL,
                candidate
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("체크포인트 기록 실패");
        verify(failureRecorder, never()).resolve(
                ExternalDataSource.LH_ANNOUNCEMENT_DETAIL,
                candidate.requestDescription()
        );
        verify(failureRecorder, never()).resolve(
                ExternalDataSource.LH_ANNOUNCEMENT_DETAIL,
                candidate.sourceDescription()
        );
    }

    private Candidate candidate() {
        LhAnnouncementRequest request = new LhAnnouncementRequest("100", "03", "06", "48", "063");
        return new Candidate("announcement-1", "myhomeAnnouncementSourceId=1", request);
    }
}
