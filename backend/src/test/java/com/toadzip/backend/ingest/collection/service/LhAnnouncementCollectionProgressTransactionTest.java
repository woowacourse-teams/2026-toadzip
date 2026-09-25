package com.toadzip.backend.ingest.collection.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.collection.dto.LhAnnouncementRequest;
import com.toadzip.backend.ingest.collection.repository.LhAnnouncementCollectionCheckpointRepository;
import com.toadzip.backend.ingest.collection.repository.LhAnnouncementCollectionLinkRepository;
import com.toadzip.backend.ingest.collection.service.LhAnnouncementCollectionCandidateResolver.Candidate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@ActiveProfiles("test")
class LhAnnouncementCollectionProgressTransactionTest {

    @Autowired
    private LhAnnouncementCollectionProgressManager progressManager;

    @Autowired
    private LhAnnouncementCollectionCheckpointRepository checkpointRepository;

    @Autowired
    private LhAnnouncementCollectionLinkRepository linkRepository;

    @MockitoBean
    private ExternalDataFailureRecorder failureRecorder;

    @AfterEach
    void cleanUp() {
        linkRepository.deleteAll();
        checkpointRepository.deleteAll();
    }

    @Test
    void 실패_기록_갱신이_실패하면_체크포인트와_공고_연결을_함께_롤백한다() {
        Candidate candidate = new Candidate(
                "announcement-1",
                "myhomeAnnouncementSourceId=1",
                new LhAnnouncementRequest("PAN-1", "03", "06", "48", "063")
        );
        doThrow(new IllegalStateException("실패 기록 갱신 실패"))
                .when(failureRecorder)
                .resolve(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY, candidate.requestDescription());

        assertThatThrownBy(() -> progressManager.complete(
                ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY, candidate
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("실패 기록 갱신 실패");

        assertThat(checkpointRepository.count()).isZero();
        assertThat(linkRepository.count()).isZero();
    }
}
