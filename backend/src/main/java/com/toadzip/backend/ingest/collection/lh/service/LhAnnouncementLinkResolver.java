package com.toadzip.backend.ingest.collection.lh.service;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.collection.lh.domain.LhAnnouncementCollectionLink;
import com.toadzip.backend.ingest.collection.lh.dto.LhAnnouncementRequest;
import com.toadzip.backend.ingest.collection.lh.repository.LhAnnouncementCollectionLinkRepository;
import com.toadzip.backend.ingest.collection.lh.service.LhAnnouncementCollectionCandidateResolver.Candidate;
import com.toadzip.backend.ingest.collection.lh.service.LhAnnouncementCollectionCandidateResolver;
import com.toadzip.backend.ingest.collection.myhome.announcement.domain.projection.MyHomeAnnouncementSource;
import com.toadzip.backend.ingest.exception.exception.LhAnnouncementLinkResolutionException.Reason;
import com.toadzip.backend.ingest.exception.exception.LhAnnouncementLinkResolutionException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class LhAnnouncementLinkResolver {

    private final LhAnnouncementCollectionCandidateResolver candidateResolver;
    private final LhAnnouncementCollectionLinkRepository linkRepository;

    @Transactional(readOnly = true)
    public LhAnnouncementRequest resolve(MyHomeAnnouncementSource source) {
        var resolution = candidateResolver.resolve(source);
        if (!(resolution instanceof Candidate candidate)) {
            throw new LhAnnouncementLinkResolutionException(
                    Reason.REQUEST_UNSUPPORTED, "현재 마이홈 공고에서 LH 수집 요청을 확정할 수 없습니다."
            );
        }
        validateLink(candidate, ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY);
        validateLink(candidate, ExternalDataSource.LH_ANNOUNCEMENT_DETAIL);
        return candidate.request();
    }

    @Transactional(readOnly = true)
    public LinkedSource resolveFirstLinked(List<MyHomeAnnouncementSource> sources) {
        LhAnnouncementLinkResolutionException firstFailure = null;
        for (MyHomeAnnouncementSource source : sources) {
            try {
                return new LinkedSource(source, resolve(source));
            }
            catch (LhAnnouncementLinkResolutionException exception) {
                if (firstFailure == null || firstFailure.reason() == Reason.REQUEST_UNSUPPORTED
                        && exception.reason() != Reason.REQUEST_UNSUPPORTED) {
                    firstFailure = exception;
                }
            }
        }
        if (firstFailure != null) {
            throw firstFailure;
        }
        throw new IllegalArgumentException("LH 공고 원천이 없습니다.");
    }

    public record LinkedSource(MyHomeAnnouncementSource source, LhAnnouncementRequest request) {
    }

    private void validateLink(Candidate candidate, ExternalDataSource targetSource) {
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
    }
}
