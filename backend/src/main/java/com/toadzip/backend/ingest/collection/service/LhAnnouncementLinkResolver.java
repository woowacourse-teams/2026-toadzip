package com.toadzip.backend.ingest.collection.service;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.collection.domain.LhAnnouncementCollectionLink;
import com.toadzip.backend.ingest.collection.domain.MyHomeAnnouncementSource;
import com.toadzip.backend.ingest.collection.repository.LhAnnouncementCollectionLinkRepository;
import com.toadzip.backend.ingest.collection.service.LhAnnouncementCollectionCandidateResolver.Candidate;
import com.toadzip.backend.ingest.collection.service.LhAnnouncementLinkResolutionException.Reason;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class LhAnnouncementLinkResolver {

    private final LhAnnouncementCollectionCandidateResolver candidateResolver;
    private final LhAnnouncementCollectionLinkRepository linkRepository;

    @Transactional(readOnly = true)
    public String resolve(MyHomeAnnouncementSource source) {
        var resolution = candidateResolver.resolve(source);
        if (!(resolution instanceof Candidate candidate)) {
            throw new LhAnnouncementLinkResolutionException(
                    Reason.REQUEST_UNSUPPORTED, "현재 마이홈 공고에서 LH 수집 요청을 확정할 수 없습니다."
            );
        }
        String panId = resolve(candidate, ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY);
        resolve(candidate, ExternalDataSource.LH_ANNOUNCEMENT_DETAIL);
        return panId;
    }

    private String resolve(Candidate candidate, ExternalDataSource targetSource) {
        LhAnnouncementCollectionLink link = linkRepository
                .findBySourceAndSourceAnnouncementKey(targetSource, candidate.sourceAnnouncementKey())
                .orElseThrow(() -> new LhAnnouncementLinkResolutionException(
                        Reason.LINK_NOT_FOUND, "수집 완료된 LH 공고 연결이 없습니다: " + targetSource
                ));
        if (!link.matches(candidate.requestDescription(), candidate.panId())) {
            throw new LhAnnouncementLinkResolutionException(
                    Reason.LINK_MISMATCH, "현재 LH 수집 요청과 마지막 성공 연결이 다릅니다: " + targetSource
            );
        }
        return link.getPanId();
    }
}
