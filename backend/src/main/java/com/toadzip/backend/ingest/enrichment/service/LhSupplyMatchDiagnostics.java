package com.toadzip.backend.ingest.enrichment.service;

import com.toadzip.backend.announcement.domain.SupplyRow;
import com.toadzip.backend.announcement.repository.AnnouncementRepository;
import com.toadzip.backend.announcement.repository.SupplyRowRepository;
import com.toadzip.backend.ingest.collection.lh.supply.domain.LhAnnouncementSupplySource;
import com.toadzip.backend.ingest.enrichment.service.LhAnnouncementEnrichmentMapper.LhAnnouncementEnrichmentRejectedException;
import com.toadzip.backend.ingest.enrichment.service.LhAnnouncementEnrichmentMapper.LhSupplyData;
import com.toadzip.backend.ingest.mapping.service.LhSupplyRowLinks;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 관리자 화면에서 현재 원천과 등록된 공급행으로 LH 보강 공급행 매칭을 다시 해 보고, 실패한 LH 행마다
 * 비교할 공급행 후보를 돌려준다. 저장하지 않으며 보강과 같은 매칭 규칙을 쓴다.
 */
@Component
@RequiredArgsConstructor
public class LhSupplyMatchDiagnostics {
    private final AnnouncementRepository announcements;
    private final SupplyRowRepository supplyRows;
    private final LhAnnouncementEnrichmentMapper mapper;
    private final LhAnnouncementSupplyMatcher matcher;

    public List<Failure> diagnose(String announcementIdentifier, String panId,
            List<LhAnnouncementSupplySource> supplies, Map<String, LhSupplyRowLinks.State> supplyLinks,
            Set<String> sourceKeysExcludedFromLhEnrichment) {
        var announcement = announcements.findBySourceAnnouncementIdentifier(announcementIdentifier).orElse(null);
        if (announcement == null) {
            return List.of();
        }
        List<SupplyRow> allRows = LhAnnouncementEnrichmentWriter.enrichmentRows(announcement,
                supplyRows.findAllByAnnouncement(announcement), sourceKeysExcludedFromLhEnrichment);
        List<SupplyRow> rows = allRows.stream().filter(row -> row.getSupplyGroup() == null).toList();
        List<Failure> failures = new ArrayList<>();
        for (LhAnnouncementSupplySource supply : supplies) {
            diagnose(panId, supply, supplyLinks, allRows, rows).ifPresent(failures::add);
        }
        return failures;
    }

    private Optional<Failure> diagnose(String panId, LhAnnouncementSupplySource supply,
            Map<String, LhSupplyRowLinks.State> supplyLinks, List<SupplyRow> allRows, List<SupplyRow> rows) {
        String identifier = LhSupplyRowLinks.identifier(supply);
        LhSupplyData source;
        try {
            source = mapper.map(panId, List.of(), List.of(supply)).withSupplyLinks(supplyLinks).supplies().getFirst();
        } catch (LhAnnouncementEnrichmentRejectedException exception) {
            return Optional.of(new Failure(identifier, exception.reason().name(), exception.getMessage(),
                    List.of()));
        }
        // 복수 공급행으로 보정한 행에 붙는 LH 행은 보강이 건너뛰므로 실패로 보지 않는다.
        var protectedMatch = matcher.match(allRows, source).row();
        if (protectedMatch != null && protectedMatch.getSupplyGroup() != null) {
            return Optional.empty();
        }
        var match = matcher.match(rows, source);
        if (match.failure() == null) {
            return Optional.empty();
        }
        var reason = match.failure().reason();
        return Optional.of(new Failure(identifier, reason.name(), match.failure().detail(),
                matcher.candidates(rows, source, reason).stream().map(Candidate::of).toList()));
    }

    public record Failure(String lhRowIdentifier, String reason, String detail, List<Candidate> candidates) {
    }

    /** 비교할 등록 공급행이다. 이름은 원천 표기와 연결된 단지·주택형 이름을 함께 준다. */
    public record Candidate(long supplyRowId, String myhomeRowIdentifier, String sourceComplexName,
            String sourceHousingTypeName, String complexName, String housingTypeName,
            String lhRowIdentifier) {

        static Candidate of(SupplyRow row) {
            String complexName = null;
            if (row.getHousingComplex() != null) {
                complexName = row.getHousingComplex().getName();
            }
            String housingTypeName = null;
            if (row.getHousingType() != null) {
                housingTypeName = row.getHousingType().getName();
            }
            return new Candidate(row.getId(), row.getMyhomeSourceRowIdentifier(), row.getSourceComplexName(),
                    row.getSourceHousingTypeName(), complexName, housingTypeName,
                    row.getLhSourceSupplyRowIdentifier());
        }
    }
}
