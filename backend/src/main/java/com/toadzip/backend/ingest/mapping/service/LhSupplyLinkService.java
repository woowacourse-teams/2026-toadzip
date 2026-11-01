package com.toadzip.backend.ingest.mapping.service;

import com.toadzip.backend.admin.exception.AdminDataConflictException;
import com.toadzip.backend.announcement.repository.SupplyRowGroupRepository;
import com.toadzip.backend.ingest.collection.lh.supply.domain.LhAnnouncementSupplySourceView;
import com.toadzip.backend.ingest.collection.myhome.announcement.domain.MyHomeAnnouncementCurrentSources;
import com.toadzip.backend.ingest.collection.myhome.announcement.domain.projection.MyHomeAnnouncementSourceView;
import com.toadzip.backend.ingest.collection.myhome.announcement.repository.MyHomeAnnouncementSourceReader;
import com.toadzip.backend.ingest.enrichment.domain.LhSupplyRowLink;
import com.toadzip.backend.ingest.enrichment.repository.LhSupplyRowLinkRepository;
import com.toadzip.backend.ingest.enrichment.service.LhSupplyMatchDiagnostics;
import com.toadzip.backend.ingest.enrichment.service.LhSupplyRowLinks;
import com.toadzip.backend.ingest.exception.exception.InvalidIngestRequestException;
import com.toadzip.backend.ingest.mapping.dto.LhSupplyLinking.Announcement;
import com.toadzip.backend.ingest.mapping.dto.LhSupplyLinking.Candidate;
import com.toadzip.backend.ingest.mapping.dto.LhSupplyLinking.CurrentSupply;
import com.toadzip.backend.ingest.mapping.dto.LhSupplyLinking.LhRow;
import com.toadzip.backend.ingest.mapping.dto.LhSupplyLinking.MatchFailure;
import com.toadzip.backend.ingest.mapping.dto.LhSupplyLinking.MyHomeRow;
import com.toadzip.backend.ingest.mapping.dto.LhSupplyLinking.ReleaseRequest;
import com.toadzip.backend.ingest.mapping.dto.LhSupplyLinking.SaveRequest;
import com.toadzip.backend.ingest.mapping.dto.LhSupplyLinking.SavedLink;
import com.toadzip.backend.ingest.mapping.dto.LhSupplyLinking.View;
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionScope;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 단지명 표기가 달라 자동으로 붙지 않는 LH 공급 원본 행을 관리자가 마이홈 원천행에 연결·해제한다.
 * 저장·해제는 다음 정제와 LH 보강부터 반영된다. 이미 만든 공급행은 연결이 바뀐 LH 행의 기록과 그 LH 금액만 바로 놓는다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LhSupplyLinkService {
    private final MyHomeAnnouncementSourceReader sources;
    private final MyHomeAnnouncementSourceMapper mapper;
    private final MyHomeAnnouncementSupplyRowResolver resolver;
    private final LhSupplyRowLinks links;
    private final LhSupplyRowLinkRepository repository;
    private final SupplyRowGroupRepository groups;
    private final LhSupplyMatchDiagnostics diagnostics;
    private final LhSupplyLinkChangeLog changeLog;
    private final LhSupplyLinkReleases releases;

    public View view(String identifier) {
        return view(identifier, List.of());
    }

    /**
     * 공고의 현재 LH 공급 원본 행. 마이홈 원천이나 LH 공급 원본이 없거나 원천을 해석할 수 없으면 비어 있다.
     * LH 수집 연결 해석은 실패하면 자기 트랜잭션 경계 밖으로 예외를 던진다. 호출한 보정 트랜잭션이 롤백 전용이 되지 않도록
     * 트랜잭션 없이 읽어 해석 실패가 그 해석의 트랜잭션에서만 끝나게 한다.
     */
    @Transactional(readOnly = true, propagation = Propagation.NOT_SUPPORTED)
    public List<CurrentSupply> currentSupplies(String identifier) {
        try {
            return context(identifier).supplies().stream().map(supply -> new CurrentSupply(
                    LhSupplyRowLinks.identifier(supply), supply.getComplexLabel(), supply.getTypeName(),
                    supply.getExclusiveArea())).toList();
        }
        catch (InvalidIngestRequestException unavailable) {
            return List.of();
        }
    }

    private View view(String identifier, List<String> releasedSupplyMatches) {
        var context = context(identifier);
        var myhomeRows = context.currentSources().stream().map(source -> new MyHomeRow(source.getSourceKey(),
                source.getHsmpNm(), source.getHouseTyNm(), source.getSumSuplyCo(),
                groups.findBySourceRowIdentifier(source.getSourceKey()).isPresent())).toList();
        var lhRows = context.supplies().stream().map(supply -> lhRow(context, supply)).toList();
        var first = context.currentSources().getFirst();
        var announcement = new Announcement(identifier, first.getPblancNm(), first.getUrl(), context.panId());
        var failures = diagnostics.diagnose(identifier, context.panId(), context.supplies(), context.states(),
                context.excludedSourceKeys()).stream().map(this::failure).toList();
        return new View(context.token(), announcement, myhomeRows, lhRows, failures, releasedSupplyMatches);
    }

    private MatchFailure failure(LhSupplyMatchDiagnostics.Failure failure) {
        return new MatchFailure(failure.lhRowIdentifier(), failure.reason(), failure.detail(),
                failure.candidates().stream().map(value -> new Candidate(value.supplyRowId(),
                        value.myhomeRowIdentifier(), value.sourceComplexName(), value.sourceHousingTypeName(),
                        value.complexName(), value.housingTypeName(), value.lhRowIdentifier())).toList());
    }

    @Transactional
    public View save(String identifier, SaveRequest request, String actor) {
        // POST 인터셉터가 트랜잭션 종료까지 전역 수집 잠금을 유지한다.
        IngestExecutionScope.verifyHeld();
        SupplyMatchingEvidence.validate(request.sourceUrl(), request.reason());
        var context = verifiedContext(identifier, request.token());
        var myhome = context.currentSources().stream()
                .filter(source -> source.getSourceKey().equals(request.myhomeRowIdentifier())).findFirst()
                .orElseThrow(() -> new InvalidIngestRequestException("해당 공고의 현재 마이홈 원천행이 아닙니다."));
        if (groups.findBySourceRowIdentifier(myhome.getSourceKey()).isPresent()) {
            throw new AdminDataConflictException("복수 공급행으로 보정한 원천행은 LH 공급 연결 대상이 아닙니다.");
        }
        if (request.lhRowIdentifiers().stream().distinct().count() != request.lhRowIdentifiers().size()) {
            throw new InvalidIngestRequestException("같은 LH 공급행을 중복 선택할 수 없습니다.");
        }
        long linkId = 0;
        for (String lhRowIdentifier : request.lhRowIdentifiers()) {
            var supply = context.supply(lhRowIdentifier)
                    .orElseThrow(() -> new InvalidIngestRequestException("해당 공고의 LH 공급 원본 행이 아닙니다."));
            var link = repository.findBySourceAnnouncementIdentifierAndLhSupplyRowIdentifier(identifier,
                    lhRowIdentifier).orElseGet(() -> new LhSupplyRowLink(identifier, lhRowIdentifier));
            SavedLink before = savedLink(link, false);
            link.confirm(myhome.getSourceKey(), links.lhFingerprint(supply), links.myHomeFingerprint(myhome),
                    request.sourceUrl().strip(), request.reason().strip(), actor);
            repository.saveAndFlush(link);
            changeLog.linkSaved(link.getId(), identifier, lhRowIdentifier, before, savedLink(link, false), actor);
            linkId = link.getId();
        }
        return view(identifier, releaseChangedSources(identifier, context, linkId, actor));
    }

    @Transactional
    public View release(String identifier, ReleaseRequest request, String actor) {
        IngestExecutionScope.verifyHeld();
        var context = verifiedContext(identifier, request.token());
        var state = context.states().get(request.lhRowIdentifier());
        if (state == null) {
            throw new AdminDataConflictException("저장한 LH 공급 연결이 없습니다. 다시 조회해 주세요.");
        }
        long linkId = state.link().getId();
        SavedLink released = savedLink(state.link(), !state.valid());
        repository.delete(state.link());
        repository.flush();
        changeLog.linkReleased(linkId, identifier, request.lhRowIdentifier(), released, request.reason().strip(),
                actor);
        return view(identifier, releaseChangedSources(identifier, context, linkId, actor));
    }

    /** 연결을 바꾼 뒤의 원천 해석을 바꾸기 전과 비교해, 연결이 바뀐 원천행의 저장 매칭과 이전 공급행의 LH 기록을 놓는다. */
    private List<String> releaseChangedSources(String identifier, Context before, long linkId, String actor) {
        var after = context(identifier);
        return releases.release(identifier, before.linkedRows(), after.linkedRows(), after.supplies(), linkId, actor);
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

    private Context verifiedContext(String identifier, String token) {
        var context = context(identifier);
        if (!context.token().equals(token)) {
            throw new AdminDataConflictException("원천 또는 저장한 연결이 변경되었습니다. 다시 조회해 주세요.");
        }
        return context;
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

        Optional<LhAnnouncementSupplySourceView> supply(String identifier) {
            return supplies.stream().filter(value -> LhSupplyRowLinks.identifier(value).equals(identifier))
                    .findFirst();
        }
    }
}
