package com.toadzip.backend.ingest.mapping.service;

import com.toadzip.backend.announcement.repository.AnnouncementRepository;
import com.toadzip.backend.ingest.collection.lh.supply.domain.LhAnnouncementSupplySourceView;
import com.toadzip.backend.ingest.domain.LhSourceIdentifier;
import com.toadzip.backend.ingest.enrichment.service.LhSupplyRowLinks;
import com.toadzip.backend.ingest.enrichment.service.MappedAnnouncementLhEnrichment;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 관리자가 LH 공급 연결을 바꾼 뒤 그 변경 때문에 맞지 않게 된 저장 매칭과 이전 공급행의 LH 기록·금액을 같은 트랜잭션에서 놓는다. */
@Component
@RequiredArgsConstructor
class LhSupplyLinkReleases {

    private final SavedSupplyMatches savedMatches;
    private final AnnouncementRepository announcements;
    private final MappedAnnouncementLhEnrichment lhEnrichment;
    private final LhSupplyLinkChangeLog changeLog;

    /**
     * LH 행 구성이 바뀐 마이홈 원천행은 다음 정제에서 LH 주택형별로 다시 펼쳐져 저장 매칭의 원천 지문이 반드시 어긋난다.
     * 정제가 거절되지 않도록 그 원천행과 펼친 행의 저장 매칭을 함께 해제한다. 주택형은 다시 정제한 뒤 PDF 보정에서 확인한다.
     * 재정제 전 보강이 이전 행에 금액을 붙이지 않도록, 연결이 바뀐 LH 행을 기록한 공급행 가운데 그 LH 행이 이제 다른 원천행으로
     * 해석되는 행의 LH 연결 기록도 놓는다. 보강이 이름으로 맞춰 기록한 행도 포함한다.
     * 관리자가 그 LH 행이 이 원천행의 것이 아니라고 확인했으므로 이전 행에 붙은 그 LH 금액도 지운다. 관리자가 고친 금액은 둔다.
     */
    List<String> release(
            String identifier, Map<String, String> beforeLinkedRows, Map<String, String> afterLinkedRows,
            List<LhAnnouncementSupplySourceView> supplies, long linkId, String actor
    ) {
        Set<String> changedSources = new HashSet<>();
        Set<String> movedLhRows = new HashSet<>();
        for (var supply : supplies) {
            String lhRowIdentifier = LhSupplyRowLinks.identifier(supply);
            String previous = beforeLinkedRows.get(lhRowIdentifier);
            String current = afterLinkedRows.get(lhRowIdentifier);
            if (!Objects.equals(previous, current)) {
                Optional.ofNullable(previous).ifPresent(changedSources::add);
                Optional.ofNullable(current).ifPresent(changedSources::add);
                movedLhRows.add(lhRowIdentifier);
            }
        }
        announcements.findBySourceAnnouncementIdentifier(identifier)
                .ifPresent(announcement -> lhEnrichment.releaseMovedLhRows(announcement, afterLinkedRows, movedLhRows));
        Set<String> rowIdentifiers = new TreeSet<>(changedSources);
        for (var supply : supplies) {
            String lhRowIdentifier = LhSupplyRowLinks.identifier(supply);
            if (changedSources.contains(beforeLinkedRows.get(lhRowIdentifier))
                    || changedSources.contains(afterLinkedRows.get(lhRowIdentifier))) {
                rowIdentifiers.add(LhSourceIdentifier.supplyRow(identifier, supply.getPanId(), supply.getSourceOrder()));
            }
        }
        List<String> released = new ArrayList<>();
        for (String rowIdentifier : rowIdentifiers) {
            savedMatches.release(rowIdentifier).ifPresent(decision -> {
                changeLog.matchReleased(linkId, identifier, rowIdentifier, decision, actor);
                released.add(rowIdentifier);
            });
        }
        return released;
    }
}
