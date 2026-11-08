package com.toadzip.backend.ingest.sh.refinement.service;

import com.toadzip.backend.ingest.sh.collection.domain.ShAnnouncementSource;
import com.toadzip.backend.ingest.sh.collection.repository.ShAnnouncementSourceRepository;
import com.toadzip.backend.ingest.sh.refinement.dto.ShAnnouncementRefinementReport;

/** 저장된 SH 원천 전부를 한 페이지처럼 정제해 정제 규칙을 검증한다. */
public final class ShRefinementTestSupport {

    private ShRefinementTestSupport() {
    }

    public static ShAnnouncementRefinementReport refineAll(
            ShAnnouncementRefinementService service, ShAnnouncementSourceRepository sources
    ) {
        var run = service.begin();
        run.refinePage(sources.findAll().stream().map(ShAnnouncementSource::getSeq).toList());
        return run.report();
    }
}
