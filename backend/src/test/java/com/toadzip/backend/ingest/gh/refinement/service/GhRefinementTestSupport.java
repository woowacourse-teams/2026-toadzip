package com.toadzip.backend.ingest.gh.refinement.service;

import com.toadzip.backend.ingest.gh.collection.domain.GhAnnouncementSource;
import com.toadzip.backend.ingest.gh.collection.repository.GhAnnouncementSourceRepository;
import com.toadzip.backend.ingest.gh.refinement.dto.GhAnnouncementRefinementReport;

/** 저장된 GH 원천 전부를 한 페이지처럼 정제해 정제 규칙을 검증한다. */
public final class GhRefinementTestSupport {

    private GhRefinementTestSupport() {
    }

    public static GhAnnouncementRefinementReport refineAll(
            GhAnnouncementRefinementService service, GhAnnouncementSourceRepository sources
    ) {
        var run = service.begin();
        run.refinePage(sources.findAll().stream().map(GhAnnouncementSource::getPbancNo).toList());
        return run.report();
    }
}
