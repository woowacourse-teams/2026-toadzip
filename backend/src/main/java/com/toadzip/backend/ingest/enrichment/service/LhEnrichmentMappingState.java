package com.toadzip.backend.ingest.enrichment.service;

import com.toadzip.backend.ingest.collection.myhome.announcement.domain.projection.MyHomeAnnouncementSource;
import java.util.List;
import java.util.Set;

/**
 * LH 보강이 마이홈 공고 정제에서 확인하는 상태다. 정제 구현이 바뀌어도 보강은 이 계약만 본다.
 */
public interface LhEnrichmentMappingState {

    /** 정제 실패가 해소되지 않아 보강을 미룰 공고 식별자다. */
    Set<String> incompleteAnnouncementIdentifiers();

    /** 공고 공통값을 정제할 수 없으면 그 사유를, 정제할 수 있으면 null을 돌려준다. */
    String commonValuesRejection(List<MyHomeAnnouncementSource> sources);
}
