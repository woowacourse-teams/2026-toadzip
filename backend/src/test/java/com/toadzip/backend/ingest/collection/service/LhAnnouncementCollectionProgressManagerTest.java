package com.toadzip.backend.ingest.collection.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.collection.lh.dto.LhAnnouncementRequest;
import com.toadzip.backend.ingest.collection.lh.repository.LhAnnouncementCollectionProgressStore;
import com.toadzip.backend.ingest.collection.lh.service.LhAnnouncementCollectionCandidateResolver.Candidate;
import com.toadzip.backend.ingest.collection.lh.service.LhAnnouncementCollectionProgressManager;
import com.toadzip.backend.ingest.failure.service.ExternalDataFailureRecorder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LhAnnouncementCollectionProgressManagerTest {


    @Mock
    private LhAnnouncementCollectionProgressStore progressStore;

    @Mock
    private ExternalDataFailureRecorder failureRecorder;

    private LhAnnouncementCollectionProgressManager progressManager;

    @BeforeEach
    void setUp() {
        progressManager = new LhAnnouncementCollectionProgressManager(
                progressStore,
                failureRecorder
        );
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
    void 수집된_공유_요청을_공고에_연결할_때는_체크포인트를_갱신하지_않는다() {
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
