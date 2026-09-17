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
import java.util.List;
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
        progressManager = new LhAnnouncementCollectionProgressManager(progressStore, failureRecorder);
    }

    @Test
    void 후보의_요청과_PAN_ID로_배치_진행_상태를_조회한다() {
        Candidate candidate = candidate();
        BatchProgress expected = BatchProgress.empty();
        when(progressStore.findBatch(
                ExternalDataSource.LH_ANNOUNCEMENT_DETAIL,
                List.of(candidate.requestDescription()),
                List.of(candidate.panId()),
                List.of(candidate.sourceAnnouncementKey())
        )).thenReturn(expected);

        BatchProgress result = progressManager.findBatch(
                ExternalDataSource.LH_ANNOUNCEMENT_DETAIL,
                List.of(candidate)
        );

        assertThat(result).isSameAs(expected);
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
