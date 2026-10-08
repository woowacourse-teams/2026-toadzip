package com.toadzip.backend.ingest.collection.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.collection.lh.domain.LhAnnouncementQuery;
import com.toadzip.backend.ingest.collection.lh.dto.LhAnnouncementRequest;
import com.toadzip.backend.ingest.collection.lh.service.LhAnnouncementCollectionCandidateResolver;
import com.toadzip.backend.ingest.collection.lh.supply.dto.VerifiedLhSupplyReplacementRequest;
import com.toadzip.backend.ingest.collection.lh.supply.repository.VerifiedLhSupplyReplacementStore;
import com.toadzip.backend.ingest.collection.lh.supply.service.VerifiedLhSupplyReplacementService;
import com.toadzip.backend.ingest.collection.myhome.announcement.domain.projection.MyHomeAnnouncementSource;
import com.toadzip.backend.ingest.collection.myhome.announcement.repository.MyHomeAnnouncementSourceReader;
import com.toadzip.backend.ingest.exception.exception.InvalidIngestRequestException;
import com.toadzip.backend.ingest.failure.domain.ExternalDataCollectionFailure;
import com.toadzip.backend.ingest.failure.repository.ExternalDataCollectionFailureRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class VerifiedLhSupplyReplacementServiceTest {

    private static final String FINGERPRINT = "a".repeat(64);
    private static final LhAnnouncementRequest LH_REQUEST = new LhAnnouncementRequest(
            "pan-1", "03", "06", "07", "062"
    );

    private final ExternalDataCollectionFailureRepository failureRepository =
            mock(ExternalDataCollectionFailureRepository.class);
    private final VerifiedLhSupplyReplacementStore replacementStore = mock(VerifiedLhSupplyReplacementStore.class);
    private final MyHomeAnnouncementSourceReader myHomeRepository = mock(MyHomeAnnouncementSourceReader.class);
    private final LhAnnouncementCollectionCandidateResolver resolver = mock(LhAnnouncementCollectionCandidateResolver.class);
    private final VerifiedLhSupplyReplacementService service = new VerifiedLhSupplyReplacementService(
            failureRepository, replacementStore, myHomeRepository, resolver
    );

    @BeforeEach
    void setUp() {
        MyHomeAnnouncementSource source = mock(MyHomeAnnouncementSource.class);
        when(source.isActive()).thenReturn(true);
        when(myHomeRepository.findAllByPblancIdOrderByIdAsc("pblanc-1")).thenReturn(List.of(source));
        when(resolver.resolveAll(List.of(source))).thenReturn(List.of(
                new LhAnnouncementCollectionCandidateResolver.Candidate(
                        "pblanc-1", "source", LH_REQUEST
                )
        ));
    }

    @Test
    void 보류_응답의_지문과_LH_공식_근거가_일치하면_승인을_기록한다() {
        String description = LH_REQUEST.requestDescription();
        when(failureRepository.findFirstBySourceAndRequestDescriptionOrderByIdDesc(
                ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY, description))
                .thenReturn(Optional.of(failure("proposedFingerprint=" + FINGERPRINT)));

        service.approve("pblanc-1", request(FINGERPRINT, "https://apply.lh.or.kr/notice"), "operator");

        verify(replacementStore).approve(LhAnnouncementQuery.requestHashOf(description),
                FINGERPRINT, "https://apply.lh.or.kr/notice", "주택형 철회 확인", "operator");
    }

    @Test
    void 다른_응답의_지문은_승인하지_않는다() {
        when(failureRepository.findFirstBySourceAndRequestDescriptionOrderByIdDesc(
                ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY, LH_REQUEST.requestDescription()))
                .thenReturn(Optional.of(failure("proposedFingerprint=" + "b".repeat(64))));

        assertThatThrownBy(() -> service.approve("pblanc-1",
                request(FINGERPRINT, "https://apply.lh.or.kr/notice"), "operator"))
                .isInstanceOf(InvalidIngestRequestException.class);
    }

    @Test
    void 외부_도메인의_근거는_승인하지_않는다() {
        assertThatThrownBy(() -> service.approve("pblanc-1",
                request(FINGERPRINT, "https://example.com/notice"), "operator"))
                .isInstanceOf(InvalidIngestRequestException.class);
    }

    private VerifiedLhSupplyReplacementRequest request(String fingerprint, String evidenceUrl) {
        return new VerifiedLhSupplyReplacementRequest(
                LH_REQUEST.requestDescription(), fingerprint, evidenceUrl, "주택형 철회 확인"
        );
    }

    private ExternalDataCollectionFailure failure(String reason) {
        return ExternalDataCollectionFailure.create(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY,
                LH_REQUEST.requestDescription(), Instant.parse("2026-09-28T00:00:00Z"), 0,
                "IncompleteLhSupplyReplacementException", reason);
    }
}
