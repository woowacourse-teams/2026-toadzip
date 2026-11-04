package com.toadzip.backend.ingest.lh.collection.supply.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.lh.collection.domain.LhAnnouncementQuery;
import com.toadzip.backend.ingest.lh.collection.dto.LhAnnouncementRequest;
import com.toadzip.backend.ingest.lh.collection.service.LhAnnouncementCollectionCandidateResolver;
import com.toadzip.backend.ingest.lh.collection.supply.domain.LhSupplySnapshot;
import com.toadzip.backend.ingest.lh.collection.supply.dto.VerifiedLhSupplyReplacementRequest;
import com.toadzip.backend.ingest.lh.collection.supply.repository.VerifiedLhSupplyReplacementStore;
import com.toadzip.backend.ingest.lh.collection.supply.service.VerifiedLhSupplyReplacementService;
import com.toadzip.backend.ingest.exception.exception.InvalidIngestRequestException;
import com.toadzip.backend.ingest.failure.domain.ExternalDataCollectionFailure;
import com.toadzip.backend.ingest.failure.repository.ExternalDataCollectionFailureRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
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
    private final LhAnnouncementCollectionCandidateResolver resolver = mock(LhAnnouncementCollectionCandidateResolver.class);
    private final VerifiedLhSupplyReplacementService service = new VerifiedLhSupplyReplacementService(
            failureRepository, replacementStore, resolver
    );

    @BeforeEach
    void setUp() {
        when(resolver.resolveCatalogRequests()).thenReturn(List.of(
                new LhAnnouncementCollectionCandidateResolver.Candidate("lh-catalog:x", "catalog", LH_REQUEST)));
    }

    @Test
    void 보류_응답의_지문과_LH_공식_근거가_일치하면_승인을_기록한다() {
        String description = LH_REQUEST.requestDescription();
        when(failureRepository.findFirstBySourceAndRequestDescriptionOrderByIdDesc(
                ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY, description))
                .thenReturn(Optional.of(failure("proposedFingerprint=" + FINGERPRINT)));

        service.approve("pan-1", request(FINGERPRINT, "https://apply.lh.or.kr/notice"), "operator");

        verify(replacementStore).approve(LhAnnouncementQuery.requestHashOf(description),
                FINGERPRINT, "https://apply.lh.or.kr/notice", "주택형 철회 확인", "operator");
    }

    @Test
    void 원천_식별자_형식의_LH_공고_ID도_공고목록의_요청으로_승인한다() {
        String description = LH_REQUEST.requestDescription();
        when(failureRepository.findFirstBySourceAndRequestDescriptionOrderByIdDesc(
                ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY, description))
                .thenReturn(Optional.of(failure("proposedFingerprint=" + FINGERPRINT)));

        service.approve("LH:" + LH_REQUEST.panId(), request(FINGERPRINT, "https://apply.lh.or.kr/notice"), "operator");

        verify(replacementStore).approve(LhAnnouncementQuery.requestHashOf(description),
                FINGERPRINT, "https://apply.lh.or.kr/notice", "주택형 철회 확인", "operator");
        // 다른 LH 공고 ID나 마이홈 공고 ID로는 같은 요청을 승인하지 않는다.
        assertThatThrownBy(() -> service.approve("pblanc-1",
                request(FINGERPRINT, "https://apply.lh.or.kr/notice"), "operator"))
                .isInstanceOf(InvalidIngestRequestException.class);
        assertThatThrownBy(() -> service.approve("LH:other",
                request(FINGERPRINT, "https://apply.lh.or.kr/notice"), "operator"))
                .isInstanceOf(InvalidIngestRequestException.class);
    }

    @Test
    void 다른_응답의_지문은_승인하지_않는다() {
        when(failureRepository.findFirstBySourceAndRequestDescriptionOrderByIdDesc(
                ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY, LH_REQUEST.requestDescription()))
                .thenReturn(Optional.of(failure("proposedFingerprint=" + "b".repeat(64))));

        assertThatThrownBy(() -> service.approve("pan-1",
                request(FINGERPRINT, "https://apply.lh.or.kr/notice"), "operator"))
                .isInstanceOf(InvalidIngestRequestException.class);
    }

    @Test
    void 기록된_공급_감소_사유의_제안_지문으로_승인한다() {
        String reason = "LH 공급 수집 결과에 기존 공급행 3건이 누락되어 원천을 교체하지 않습니다. proposedFingerprint="
                + FINGERPRINT;
        held("IncompleteLhSupplyReplacementException", reason);

        service.approve("pan-1", request(FINGERPRINT, "https://apply.lh.or.kr/notice"), "operator");

        verify(replacementStore).approve(LhAnnouncementQuery.requestHashOf(LH_REQUEST.requestDescription()),
                FINGERPRINT, "https://apply.lh.or.kr/notice", "주택형 철회 확인", "operator");
    }

    @Test
    void 지문_없는_공급_감소_보류는_승인하지_않는다() {
        held("IncompleteLhSupplyReplacementException",
                "LH 공급 수집 결과에 기존 공급행 1건이 누락되어 원천을 교체하지 않습니다.");

        assertThatThrownBy(() -> service.approve("pan-1",
                request(FINGERPRINT, "https://apply.lh.or.kr/notice"), "operator"))
                .isInstanceOf(InvalidIngestRequestException.class)
                .hasMessage("보류된 공급 응답의 지문과 승인 대상이 다릅니다.");
        verifyNoInteractions(replacementStore);
    }

    @Test
    void 빈_공급_보류는_빈_공급_지문으로_승인한다() {
        String emptyFingerprint = LhSupplySnapshot.fingerprint(List.of());
        held("EmptyLhSupplyReplacementException", "기존 LH 공급 원천을 빈 수집 결과로 교체할 수 없습니다.");

        service.approve("pan-1", request(emptyFingerprint, "https://apply.lh.or.kr/notice"), "operator");

        verify(replacementStore).approve(LhAnnouncementQuery.requestHashOf(LH_REQUEST.requestDescription()),
                emptyFingerprint, "https://apply.lh.or.kr/notice", "주택형 철회 확인", "operator");
    }

    @Test
    void 빈_공급_보류는_다른_지문으로_승인하지_않는다() {
        held("EmptyLhSupplyReplacementException", "기존 LH 공급 원천을 빈 수집 결과로 교체할 수 없습니다.");

        assertThatThrownBy(() -> service.approve("pan-1",
                request(FINGERPRINT, "https://apply.lh.or.kr/notice"), "operator"))
                .isInstanceOf(InvalidIngestRequestException.class)
                .hasMessage("보류된 공급 응답의 지문과 승인 대상이 다릅니다.");
        verifyNoInteractions(replacementStore);
    }

    @Test
    void 공급_감소가_아닌_실패는_지문이_같아도_승인하지_않는다() {
        held("ExternalDataRequestException", "proposedFingerprint=" + FINGERPRINT);

        assertThatThrownBy(() -> service.approve("pan-1",
                request(FINGERPRINT, "https://apply.lh.or.kr/notice"), "operator"))
                .isInstanceOf(InvalidIngestRequestException.class)
                .hasMessage("공급 감소로 보류된 요청만 승인할 수 있습니다.");
        verifyNoInteractions(replacementStore);
    }

    @Test
    void 이미_해결한_보류는_지문이_같아도_승인하지_않는다() {
        ExternalDataCollectionFailure resolved = failure("proposedFingerprint=" + FINGERPRINT);
        resolved.resolve(Instant.parse("2026-09-29T00:00:00Z"), UUID.randomUUID());
        when(failureRepository.findFirstBySourceAndRequestDescriptionOrderByIdDesc(
                ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY, LH_REQUEST.requestDescription()))
                .thenReturn(Optional.of(resolved));

        assertThatThrownBy(() -> service.approve("pan-1",
                request(FINGERPRINT, "https://apply.lh.or.kr/notice"), "operator"))
                .isInstanceOf(InvalidIngestRequestException.class)
                .hasMessage("공급 감소로 보류된 요청만 승인할 수 있습니다.");
        verifyNoInteractions(replacementStore);
    }

    @Test
    void 외부_도메인의_근거는_승인하지_않는다() {
        assertThatThrownBy(() -> service.approve("pan-1",
                request(FINGERPRINT, "https://example.com/notice"), "operator"))
                .isInstanceOf(InvalidIngestRequestException.class);
    }

    private VerifiedLhSupplyReplacementRequest request(String fingerprint, String evidenceUrl) {
        return new VerifiedLhSupplyReplacementRequest(
                LH_REQUEST.requestDescription(), fingerprint, evidenceUrl, "주택형 철회 확인"
        );
    }

    private void held(String errorType, String reason) {
        when(failureRepository.findFirstBySourceAndRequestDescriptionOrderByIdDesc(
                ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY, LH_REQUEST.requestDescription()))
                .thenReturn(Optional.of(ExternalDataCollectionFailure.create(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY,
                        LH_REQUEST.requestDescription(), Instant.parse("2026-09-28T00:00:00Z"), 0,
                        errorType, reason)));
    }

    private ExternalDataCollectionFailure failure(String reason) {
        return ExternalDataCollectionFailure.create(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY,
                LH_REQUEST.requestDescription(), Instant.parse("2026-09-28T00:00:00Z"), 0,
                "IncompleteLhSupplyReplacementException", reason);
    }
}
