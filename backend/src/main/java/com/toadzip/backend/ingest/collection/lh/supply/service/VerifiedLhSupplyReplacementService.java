package com.toadzip.backend.ingest.collection.lh.supply.service;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.collection.lh.domain.LhAnnouncementQuery;
import com.toadzip.backend.ingest.collection.lh.service.LhAnnouncementCollectionCandidateResolver;
import com.toadzip.backend.ingest.collection.lh.supply.domain.LhSupplySnapshot;
import com.toadzip.backend.ingest.collection.lh.supply.dto.VerifiedLhSupplyReplacementRequest;
import com.toadzip.backend.ingest.collection.lh.supply.repository.VerifiedLhSupplyReplacementStore;
import com.toadzip.backend.ingest.collection.myhome.announcement.domain.MyHomeAnnouncementCurrentSources;
import com.toadzip.backend.ingest.collection.myhome.announcement.repository.MyHomeAnnouncementSourceReader;
import com.toadzip.backend.ingest.exception.exception.InvalidIngestRequestException;
import com.toadzip.backend.ingest.failure.domain.ExternalDataCollectionFailure;
import com.toadzip.backend.ingest.failure.domain.ExternalDataFailureStatus;
import com.toadzip.backend.ingest.failure.repository.ExternalDataCollectionFailureRepository;
import java.net.URI;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VerifiedLhSupplyReplacementService {

    private final ExternalDataCollectionFailureRepository failureRepository;
    private final VerifiedLhSupplyReplacementStore replacementStore;

    private final MyHomeAnnouncementSourceReader myHomeRepository;

    private final LhAnnouncementCollectionCandidateResolver candidateResolver;

    private static final String LH_ANNOUNCEMENT_PREFIX = "LH:";

    public VerifiedLhSupplyReplacementService(
            ExternalDataCollectionFailureRepository failureRepository,
            VerifiedLhSupplyReplacementStore replacementStore,
            MyHomeAnnouncementSourceReader myHomeRepository,
            LhAnnouncementCollectionCandidateResolver candidateResolver) {
        this.failureRepository = failureRepository;
        this.replacementStore = replacementStore;
        this.myHomeRepository = myHomeRepository;
        this.candidateResolver = candidateResolver;
    }

    @Transactional
    public long approve(String pblancId, VerifiedLhSupplyReplacementRequest request, String operator) {
        if (!officialLhUrl(request.evidenceUrl())) {
            throw new InvalidIngestRequestException("LH 공식 공고문 또는 정정 근거 URL이 필요합니다.");
        }
        if (!matchesCurrentRequest(pblancId, request.requestDescription())) {
            throw new InvalidIngestRequestException("현재 공고의 LH 공급 요청과 승인 대상이 다릅니다.");
        }
        ExternalDataCollectionFailure failure = failureRepository
                .findFirstBySourceAndRequestDescriptionOrderByIdDesc(
                        ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY, request.requestDescription())
                .orElseThrow(() -> new InvalidIngestRequestException("보류된 LH 공급 요청을 찾을 수 없습니다."));
        if (failure.getStatus() != ExternalDataFailureStatus.PENDING || !replacementFailure(failure)) {
            throw new InvalidIngestRequestException("공급 감소로 보류된 요청만 승인할 수 있습니다.");
        }
        if (!failure.getReason().contains("proposedFingerprint=" + request.proposedFingerprint())
                && !("EmptyLhSupplyReplacementException".equals(failure.getErrorType())
                && request.proposedFingerprint().equals(LhSupplySnapshot.fingerprint(List.of())))) {
            throw new InvalidIngestRequestException("보류된 공급 응답의 지문과 승인 대상이 다릅니다.");
        }
        return replacementStore.approve(
                LhAnnouncementQuery.requestHashOf(request.requestDescription()),
                request.proposedFingerprint(), request.evidenceUrl(), request.reason(), operator
        );
    }

    /**
     * 승인 대상 요청이 지금 수집하는 공고의 요청인지 본다. `LH:{공고 ID}`는 최신 공고목록의 그 공고, 그 밖은 마이홈 공고의 현재 원천으로
     * 정한 요청과 비교한다.
     */
    private boolean matchesCurrentRequest(String announcementKey, String requestDescription) {
        if (isLhAnnouncementKey(announcementKey)) {
            String panId = announcementKey.substring(LH_ANNOUNCEMENT_PREFIX.length());
            return candidateResolver.resolveCatalogRequests().stream()
                    .anyMatch(candidate -> candidate.panId().equals(panId)
                            && candidate.requestDescription().equals(requestDescription));
        }
        var currentSources = MyHomeAnnouncementCurrentSources.select(
                myHomeRepository.findAllByPblancIdOrderByIdAsc(announcementKey));
        return candidateResolver.resolveAll(currentSources)
                .stream()
                .filter(LhAnnouncementCollectionCandidateResolver.Candidate.class::isInstance)
                .map(LhAnnouncementCollectionCandidateResolver.Candidate.class::cast)
                .anyMatch(candidate -> candidate.requestDescription().equals(requestDescription));
    }

    /** 마이홈 공고가 없는 LH 공고는 `LH:{공고 ID}`로 승인한다. LH 공고 정제가 만든 공고의 원천 식별자와 같은 형식이다. */
    public static boolean isLhAnnouncementKey(String announcementKey) {
        return announcementKey.startsWith(LH_ANNOUNCEMENT_PREFIX);
    }

    @Transactional
    public boolean finish(long approvalId) {
        return replacementStore.finish(approvalId);
    }

    private boolean replacementFailure(ExternalDataCollectionFailure failure) {
        return "IncompleteLhSupplyReplacementException".equals(failure.getErrorType())
                || "EmptyLhSupplyReplacementException".equals(failure.getErrorType());
    }

    private boolean officialLhUrl(String value) {
        try {
            URI uri = URI.create(value);
            String host = uri.getHost();
            return "https".equalsIgnoreCase(uri.getScheme()) && host != null
                    && (host.equals("lh.or.kr") || host.endsWith(".lh.or.kr"));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
