package com.toadzip.backend.ingest.mapping.service;

import com.toadzip.backend.announcement.repository.SupplyRowGroupRepository;
import com.toadzip.backend.ingest.collection.lh.supply.domain.LhAnnouncementSupplySourceView;
import com.toadzip.backend.ingest.collection.myhome.announcement.domain.MyHomeAnnouncementCurrentSources;
import com.toadzip.backend.ingest.collection.myhome.announcement.domain.projection.MyHomeAnnouncementSourceView;
import com.toadzip.backend.ingest.collection.myhome.announcement.repository.MyHomeAnnouncementSourceReader;
import com.toadzip.backend.ingest.enrichment.domain.LhSupplyRowLink;
import com.toadzip.backend.ingest.enrichment.service.LhSupplyMatchDiagnostics;
import com.toadzip.backend.ingest.enrichment.service.LhSupplyRowLinks;
import com.toadzip.backend.ingest.exception.exception.InvalidIngestRequestException;
import com.toadzip.backend.ingest.mapping.dto.LhSupplyLinking.Announcement;
import com.toadzip.backend.ingest.mapping.dto.LhSupplyLinking.Candidate;
import com.toadzip.backend.ingest.mapping.dto.LhSupplyLinking.LhRow;
import com.toadzip.backend.ingest.mapping.dto.LhSupplyLinking.MatchFailure;
import com.toadzip.backend.ingest.mapping.dto.LhSupplyLinking.MyHomeRow;
import com.toadzip.backend.ingest.mapping.dto.LhSupplyLinking.SavedLink;
import com.toadzip.backend.ingest.mapping.dto.LhSupplyLinking.View;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 마이홈 원천행과 LH 공급 원본 행의 연결 상태와 자동으로 붙지 않은 사유를 보여 준다.
 * 연결 저장·해제는 마이홈 공고 정제와 함께 지원을 종료했다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LhSupplyLinkService {
    private final MyHomeAnnouncementSourceReader sources;
    private final MyHomeAnnouncementSourceMapper mapper;
    private final MyHomeAnnouncementSupplyRowResolver resolver;
    private final LhSupplyRowLinks links;
    private final SupplyRowGroupRepository groups;
    private final LhSupplyMatchDiagnostics diagnostics;

    public View view(String identifier) {
        var context = context(identifier);
        var myhomeRows = context.currentSources().stream().map(source -> new MyHomeRow(source.getSourceKey(),
                source.getHsmpNm(), source.getHouseTyNm(), source.getSumSuplyCo(),
                groups.findBySourceRowIdentifier(source.getSourceKey()).isPresent())).toList();
        var lhRows = context.supplies().stream().map(supply -> lhRow(context, supply)).toList();
        var first = context.currentSources().getFirst();
        var announcement = new Announcement(identifier, first.getPblancNm(), first.getUrl(), context.panId());
        var failures = diagnostics.diagnose(identifier, context.panId(), context.supplies(), context.states(),
                context.excludedSourceKeys()).stream().map(this::failure).toList();
        return new View(context.token(), announcement, myhomeRows, lhRows, failures, List.of());
    }

    private MatchFailure failure(LhSupplyMatchDiagnostics.Failure failure) {
        return new MatchFailure(failure.lhRowIdentifier(), failure.reason(), failure.detail(),
                failure.candidates().stream().map(value -> new Candidate(value.supplyRowId(),
                        value.myhomeRowIdentifier(), value.sourceComplexName(), value.sourceHousingTypeName(),
                        value.complexName(), value.housingTypeName(), value.lhRowIdentifier())).toList());
    }

    private LhRow lhRow(Context context, LhAnnouncementSupplySourceView supply) {
        String identifier = LhSupplyRowLinks.identifier(supply);
        var state = context.states().get(identifier);
        SavedLink saved = null;
        if (state != null) {
            saved = savedLink(state.link(), !state.valid());
        }
        return new LhRow(identifier, supply.getComplexLabel(), supply.getTypeName(), supply.getExclusiveArea(),
                supply.getSupplyArea(), supply.getSuppliedUnitCount(), context.linkedRows().get(identifier), saved);
    }

    private SavedLink savedLink(LhSupplyRowLink link, boolean reviewRequired) {
        if (link.getMyhomeSourceRowIdentifier() == null) {
            return null;
        }
        return new SavedLink(link.getMyhomeSourceRowIdentifier(), link.getSourceUrl(), link.getReason(),
                link.getActor(), link.getUpdatedAt(), reviewRequired);
    }

    private Context context(String identifier) {
        if (identifier == null || identifier.isBlank() || identifier.length() > 100) {
            throw new InvalidIngestRequestException("공고 ID가 올바르지 않습니다.");
        }
        var current = sources.findAllByPblancIdOrderByIdAsc(identifier);
        if (current.isEmpty()) {
            throw new InvalidIngestRequestException("공고 원천이 없습니다. 먼저 공고를 수집해 주세요.");
        }
        MyHomeAnnouncementSupplyRowResolver.ResolvedAnnouncement resolved;
        try {
            resolved = resolver.resolve(mapper.map(current));
        }
        catch (MyHomeAnnouncementMappingRejectedException exception) {
            throw new InvalidIngestRequestException(exception.getMessage());
        }
        if (resolved.supplies().isEmpty()) {
            throw new InvalidIngestRequestException("연결할 LH 공급 원본이 없는 공고입니다.");
        }
        var currentSources = MyHomeAnnouncementCurrentSources.select(current);
        var linkedRows = new HashMap<String, String>();
        resolved.data().supplyRows().stream().filter(row -> row.resolvedLhSourceIdentifier() != null)
                .forEach(row -> linkedRows.put(row.resolvedLhSourceIdentifier(), row.source().getSourceKey()));
        return new Context(currentSources, resolved.supplies(), resolved.supplyLinks(), linkedRows,
                links.token(currentSources, resolved.supplies(), resolved.supplyLinks()), resolved.request().panId(),
                resolved.sourceKeysExcludedFromLhEnrichment());
    }

    private record Context(List<MyHomeAnnouncementSourceView> currentSources, List<LhAnnouncementSupplySourceView> supplies,
            Map<String, LhSupplyRowLinks.State> states, Map<String, String> linkedRows, String token, String panId,
            Set<String> excludedSourceKeys) {
    }
}
