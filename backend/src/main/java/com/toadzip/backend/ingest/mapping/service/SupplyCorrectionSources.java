package com.toadzip.backend.ingest.mapping.service;

import com.toadzip.backend.admin.exception.AdminDataConflictException;
import com.toadzip.backend.announcement.repository.SupplyRowGroupRepository;
import com.toadzip.backend.ingest.collection.myhome.announcement.domain.projection.MyHomeAnnouncementSourceView;
import com.toadzip.backend.ingest.collection.myhome.announcement.repository.MyHomeAnnouncementSourceReader;
import com.toadzip.backend.ingest.exception.exception.InvalidIngestRequestException;
import com.toadzip.backend.ingest.mapping.service.MyHomeAnnouncementSourceMapper.MyHomeSupplyRowMappingData;
import com.toadzip.backend.ingest.mapping.service.MyHomeAnnouncementSupplyRowResolver.ResolvedAnnouncement;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 공급 보정이 다루는 공고의 지금 원천행. 관리자 화면이 조회한 뒤 원천, 저장한 매칭, 분할 보정이 바뀌었는지를
 * 원천행별 토큰 하나로 비교하게 한다.
 */
@Component
@RequiredArgsConstructor
class SupplyCorrectionSources {
    private final MyHomeAnnouncementSourceReader sources;
    private final MyHomeAnnouncementSourceMapper mapper;
    private final MyHomeAnnouncementSupplyRowResolver resolver;
    private final SavedSupplyMatches savedMatches;
    private final SupplyMatchingFingerprint fingerprints;
    private final SupplyRowGroupRepository groups;

    /** 정제를 되돌린 뒤 실패로 기록할 원천. 되돌릴 세션에서 다시 읽으면 쓰던 변경이 먼저 반영되므로 쓰기 전에 읽는다. */
    List<MyHomeAnnouncementSourceView> views(String identifier) {
        return sources.findAllByPblancIdOrderByIdAsc(identifier);
    }

    ResolvedAnnouncement resolved(String identifier) {
        if (identifier == null || identifier.isBlank() || identifier.length() > 100) {
            throw new InvalidIngestRequestException("공고 ID가 올바르지 않습니다.");
        }
        var current = sources.findAllByPblancIdOrderByIdAsc(identifier);
        if (current.isEmpty()) {
            throw new InvalidIngestRequestException("공고 원천이 없습니다. 먼저 공고를 수집해 주세요.");
        }
        try {
            return resolve(current);
        }
        catch (MyHomeAnnouncementMappingRejectedException exception) {
            throw new InvalidIngestRequestException(exception.getMessage());
        }
    }

    private ResolvedAnnouncement resolve(List<MyHomeAnnouncementSourceView> current) {
        return resolver.resolve(mapper.map(current));
    }

    List<MyHomeSupplyRowMappingData> supplyRows(String identifier) {
        return resolved(identifier).data().supplyRows();
    }

    /** 원천이 없거나 해석할 수 없으면 비어 있다. */
    Optional<List<MyHomeSupplyRowMappingData>> findSupplyRows(String identifier) {
        var current = sources.findAllByPblancIdOrderByIdAsc(identifier);
        if (current.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(resolve(current).data().supplyRows());
        }
        catch (MyHomeAnnouncementMappingRejectedException exception) {
            return Optional.empty();
        }
    }

    /** 저장 매칭 해제와 공개 제외가 고를 원천행. 조회 뒤 원천이나 저장한 선택이 바뀌었으면 거절한다. */
    MyHomeSupplyRowMappingData selectedRow(String identifier, String rowIdentifier, String token) {
        var data = supplyRows(identifier).stream()
                .filter(row -> row.sourceSupplyRowIdentifier().equals(rowIdentifier)).findFirst()
                .orElseThrow(() -> new InvalidIngestRequestException("해당 공고의 공급행이 아닙니다."));
        if (!token(data).equals(token)) {
            throw new AdminDataConflictException("원천 또는 저장한 선택이 변경되었습니다. 다시 조회해 주세요.");
        }
        return data;
    }

    /** 여러 공급행으로 나눌 원천행. 조회 뒤 원천이나 분할 보정이 바뀌었으면 거절한다. */
    MyHomeSupplyRowMappingData splitRow(ResolvedAnnouncement current, String rowIdentifier, String token) {
        var data = current.data().supplyRows().stream()
                .filter(value -> value.sourceSupplyRowIdentifier().equals(rowIdentifier)).findFirst()
                .orElseThrow(() -> new InvalidIngestRequestException("해당 공고의 원천행이 아닙니다."));
        if (!token(data).equals(token)) {
            throw new AdminDataConflictException("원천 또는 보정이 변경되었습니다. 다시 조회해 주세요.");
        }
        return data;
    }

    boolean isSplit(MyHomeSupplyRowMappingData data) {
        return groups.findBySourceRowIdentifier(SupplyRowGroupOrigins.key(data)).isPresent();
    }

    String token(MyHomeSupplyRowMappingData data) {
        return fingerprints.splitReview(savedMatches.reviewToken(data),
                groups.findBySourceRowIdentifier(SupplyRowGroupOrigins.key(data)).orElse(null));
    }
}
