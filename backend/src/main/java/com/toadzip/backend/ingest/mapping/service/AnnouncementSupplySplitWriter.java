package com.toadzip.backend.ingest.mapping.service;

import com.toadzip.backend.announcement.domain.SupplyRow;
import com.toadzip.backend.announcement.domain.SupplyRowGroup;
import com.toadzip.backend.announcement.repository.SupplyRowRepository;
import com.toadzip.backend.ingest.mapping.service.MyHomeAnnouncementSourceMapper.MyHomeSupplyRowMappingData;
import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class AnnouncementSupplySplitWriter {
    private final SupplyRowRepository rows;
    private final SupplyMatchingFingerprint fingerprints;

    boolean reviewRequired(SupplyRowGroup group, MyHomeSupplyRowMappingData source, boolean expanded, List<SupplyRow> members) {
        return members.size() < 2 || group.isReviewRequired()
                || !fingerprints.splitSource(source, expanded).equals(group.getSourceFingerprint())
                || members.stream().anyMatch(row -> row.getHousingComplex() == null
                || row.getHousingComplex().isAdminDeleted() || !hasConfirmedTarget(row));
    }

    /** 분할 단지를 합치기 전에 확정한 주택형이 그대로인 묶음 행만 고른다. 합친 뒤 {@link #reconfirmAfterComplexMove}로 옮긴다. */
    List<Long> confirmedRowsIn(Collection<Long> complexIds) {
        return complexIds.stream().flatMap(id -> rows.findAllByHousingComplexId(id).stream())
                .filter(this::hasConfirmedTarget).map(SupplyRow::getId).toList();
    }

    /** 같은 주택형의 소속 단지만 바뀐 경우다. 관리자 확인은 그대로 두고 대상 지문만 다시 남긴다. */
    void reconfirmAfterComplexMove(List<Long> rowIds) {
        rows.findAllById(rowIds).forEach(row ->
                row.reconfirmTargetAfterComplexMove(fingerprints.target(row.getHousingType())));
    }

    private boolean hasConfirmedTarget(SupplyRow row) {
        return row.getHousingType() != null && row.getConfirmedTargetFingerprint() != null
                && fingerprints.target(row.getHousingType()).equals(row.getConfirmedTargetFingerprint());
    }
}
