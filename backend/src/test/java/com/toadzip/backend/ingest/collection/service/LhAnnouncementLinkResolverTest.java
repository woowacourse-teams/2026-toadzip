package com.toadzip.backend.ingest.collection.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.collection.domain.LhAnnouncementCollectionLink;
import com.toadzip.backend.ingest.collection.domain.MyHomeAnnouncementSource;
import com.toadzip.backend.ingest.collection.dto.LhAnnouncementRequest;
import com.toadzip.backend.ingest.collection.repository.LhAnnouncementCollectionLinkRepository;
import com.toadzip.backend.ingest.collection.service.LhAnnouncementCollectionCandidateResolver.Candidate;
import com.toadzip.backend.ingest.collection.service.LhAnnouncementCollectionCandidateResolver.Skipped;
import com.toadzip.backend.ingest.collection.service.LhAnnouncementLinkResolutionException.Reason;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LhAnnouncementLinkResolverTest {

    private static final String KEY = "myhome-100";
    private static final LhAnnouncementRequest REQUEST = new LhAnnouncementRequest("100", "03", "06", "07", "062");

    @Mock
    private MyHomeAnnouncementSource source;

    @Mock
    private LhAnnouncementCollectionCandidateResolver candidateResolver;

    @Mock
    private LhAnnouncementCollectionLinkRepository linkRepository;

    private LhAnnouncementLinkResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new LhAnnouncementLinkResolver(candidateResolver, linkRepository);
    }

    @Test
    void 공급과_상세의_완료_연결이_모두_현재_요청과_일치해야_LH_공고를_반환한다() {
        currentRequest();
        link(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY, REQUEST.requestDescription(), "100");
        link(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL, REQUEST.requestDescription(), "100");

        assertThat(resolver.resolve(source)).isEqualTo("100");
    }

    @Test
    void 공급_연결이_없으면_연결_누락을_반환한다() {
        currentRequest();

        rejects(Reason.LINK_NOT_FOUND);
    }

    @Test
    void 상세_연결이_없어도_연결_누락을_반환한다() {
        currentRequest();
        link(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY, REQUEST.requestDescription(), "100");

        rejects(Reason.LINK_NOT_FOUND);
    }

    @Test
    void 같은_panId라도_공급_조회조건이_다르면_거절한다() {
        currentRequest();
        link(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY,
                new LhAnnouncementRequest("100", "03", "06", "08", "062").requestDescription(), "100");

        rejects(Reason.LINK_MISMATCH);
    }

    @Test
    void 상세만_과거_요청에_연결되어_있으면_거절한다() {
        currentRequest();
        link(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY, REQUEST.requestDescription(), "100");
        link(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL,
                new LhAnnouncementRequest("200", "03", "06", "07", "062").requestDescription(), "200");

        rejects(Reason.LINK_MISMATCH);
    }

    @Test
    void 요청_해시가_같아도_연결의_panId가_다르면_거절한다() {
        currentRequest();
        link(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY, REQUEST.requestDescription(), "200");

        rejects(Reason.LINK_MISMATCH);
    }

    @Test
    void 현재_수집_요청을_만들_수_없으면_URL만으로_원천을_선택하지_않는다() {
        when(candidateResolver.resolve(source)).thenReturn(new Skipped(KEY, KEY, "지원하지 않는 요청"));

        rejects(Reason.REQUEST_UNSUPPORTED);
    }

    private void currentRequest() {
        when(candidateResolver.resolve(source)).thenReturn(new Candidate(KEY, KEY, REQUEST));
    }

    private void link(ExternalDataSource target, String request, String panId) {
        when(linkRepository.findBySourceAndSourceAnnouncementKey(target, KEY)).thenReturn(Optional.of(
                LhAnnouncementCollectionLink.complete(
                        target, KEY, request, panId, Instant.parse("2026-09-16T00:00:00Z")
                )
        ));
    }

    private void rejects(Reason reason) {
        assertThatThrownBy(() -> resolver.resolve(source))
                .isInstanceOfSatisfying(LhAnnouncementLinkResolutionException.class,
                        exception -> assertThat(exception.reason()).isEqualTo(reason));
    }
}
