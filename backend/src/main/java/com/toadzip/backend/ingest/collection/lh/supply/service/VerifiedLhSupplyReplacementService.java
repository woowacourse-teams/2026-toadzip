package com.toadzip.backend.ingest.collection.lh.supply.service;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.collection.lh.domain.LhAnnouncementQuery;
import com.toadzip.backend.ingest.collection.lh.service.LhAnnouncementCollectionCandidateResolver;
import com.toadzip.backend.ingest.collection.lh.supply.domain.HeldLhSupplyReplacement;
import com.toadzip.backend.ingest.collection.lh.supply.dto.VerifiedLhSupplyReplacementRequest;
import com.toadzip.backend.ingest.collection.lh.supply.repository.VerifiedLhSupplyReplacementStore;
import com.toadzip.backend.ingest.exception.exception.InvalidIngestRequestException;
import com.toadzip.backend.ingest.failure.domain.ExternalDataCollectionFailure;
import com.toadzip.backend.ingest.failure.domain.ExternalDataFailureStatus;
import com.toadzip.backend.ingest.failure.repository.ExternalDataCollectionFailureRepository;
import java.net.URI;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VerifiedLhSupplyReplacementService {

    private final ExternalDataCollectionFailureRepository failureRepository;
    private final VerifiedLhSupplyReplacementStore replacementStore;

    private final LhAnnouncementCollectionCandidateResolver candidateResolver;

    private static final String LH_ANNOUNCEMENT_PREFIX = "LH:";

    public VerifiedLhSupplyReplacementService(
            ExternalDataCollectionFailureRepository failureRepository,
            VerifiedLhSupplyReplacementStore replacementStore,
            LhAnnouncementCollectionCandidateResolver candidateResolver) {
        this.failureRepository = failureRepository;
        this.replacementStore = replacementStore;
        this.candidateResolver = candidateResolver;
    }

    @Transactional
    public long approve(String panId, VerifiedLhSupplyReplacementRequest request, String operator) {
        if (!officialLhUrl(request.evidenceUrl())) {
            throw new InvalidIngestRequestException("LH 공식 공고문 또는 정정 근거 URL이 필요합니다.");
        }
        if (!matchesCurrentRequest(panId, request.requestDescription())) {
            throw new InvalidIngestRequestException("현재 공고의 LH 공급 요청과 승인 대상이 다릅니다.");
        }
        ExternalDataCollectionFailure failure = failureRepository
                .findFirstBySourceAndRequestDescriptionOrderByIdDesc(
                        ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY, request.requestDescription())
                .orElseThrow(() -> new InvalidIngestRequestException("보류된 LH 공급 요청을 찾을 수 없습니다."));
        if (failure.getStatus() != ExternalDataFailureStatus.PENDING
                || !HeldLhSupplyReplacement.isHeld(failure.getErrorType())) {
            throw new InvalidIngestRequestException("공급 감소로 보류된 요청만 승인할 수 있습니다.");
        }
        if (!HeldLhSupplyReplacement.proposes(
                failure.getErrorType(), failure.getReason(), request.proposedFingerprint())) {
            throw new InvalidIngestRequestException("보류된 공급 응답의 지문과 승인 대상이 다릅니다.");
        }
        return replacementStore.approve(
                LhAnnouncementQuery.requestHashOf(request.requestDescription()),
                request.proposedFingerprint(), request.evidenceUrl(), request.reason(), operator
        );
    }

    /**
     * 승인 대상 요청이 최신 공고목록에 있는 그 LH 공고(종료 정책과 관계없음)의 요청인지 본다. LH 공고 ID는
     * LH 공고 정제가 만든 공고의 원천 식별자 형식(`LH:{공고 ID}`)으로도 받는다.
     */
    private boolean matchesCurrentRequest(String announcementKey, String requestDescription) {
        String panId = announcementKey.strip();
        if (panId.startsWith(LH_ANNOUNCEMENT_PREFIX)) {
            panId = panId.substring(LH_ANNOUNCEMENT_PREFIX.length());
        }
        String target = panId;
        return candidateResolver.resolveCatalogRequests().stream()
                .anyMatch(candidate -> candidate.panId().equals(target)
                        && candidate.requestDescription().equals(requestDescription));
    }

    @Transactional
    public boolean finish(long approvalId) {
        return replacementStore.finish(approvalId);
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
