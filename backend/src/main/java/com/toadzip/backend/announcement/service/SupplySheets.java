package com.toadzip.backend.announcement.service;

import com.toadzip.backend.announcement.domain.SupplySheet;
import com.toadzip.backend.announcement.repository.LeaseTermsRepository;
import com.toadzip.backend.announcement.repository.SupplyQuotaRepository;
import com.toadzip.backend.announcement.repository.SupplyRowTargetRepository;
import java.util.Collection;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 공고 공급표 읽기 모델을 불러온다. 호출하는 쪽의 트랜잭션 안에서 쓴다.
 * 공급표 테이블은 옛 대상·수 테이블에 쓸 때 DB 트리거(V20261113_02)가 다시 만든다. PDF로 보정한 공고는 트리거가 건너뛰고
 * {@link com.toadzip.backend.announcement.repository.SupplySheetWriter}가 쓴다.
 */
@Component
@RequiredArgsConstructor
public class SupplySheets {

    private final SupplyRowTargetRepository cells;
    private final SupplyQuotaRepository quotas;
    private final LeaseTermsRepository leases;

    public SupplySheet read(Collection<Long> announcementIds, Collection<Long> supplyRowIds) {
        if (announcementIds.isEmpty() || supplyRowIds.isEmpty()) {
            return SupplySheet.empty();
        }
        return SupplySheet.of(cells.findAllBySupplyRowIdIn(supplyRowIds),
                quotas.findAllByAnnouncementIdIn(announcementIds), leases.findAllByAnnouncementIdIn(announcementIds));
    }
}
