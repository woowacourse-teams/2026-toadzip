package com.toadzip.backend.ingest.mapping.service;

import com.toadzip.backend.admin.exception.AdminDataConflictException;
import com.toadzip.backend.announcement.repository.AnnouncementRepository;
import com.toadzip.backend.housing.repository.HousingComplexRepository;
import com.toadzip.backend.housing.repository.HousingTypeRepository;
import com.toadzip.backend.ingest.collection.myhome.announcement.repository.MyHomeAnnouncementSourceReader;
import com.toadzip.backend.ingest.exception.exception.InvalidIngestRequestException;
import com.toadzip.backend.ingest.mapping.dto.AnnouncementSupplyMatching.ComplexOption;
import com.toadzip.backend.ingest.mapping.dto.AnnouncementSupplyMatching.HousingTypeOption;
import com.toadzip.backend.ingest.mapping.dto.AnnouncementSupplyMatching.RefineRequest;
import com.toadzip.backend.ingest.mapping.dto.AnnouncementSupplyMatching.Row;
import com.toadzip.backend.ingest.mapping.dto.MyHomeAnnouncementMappingReport;
import com.toadzip.backend.ingest.mapping.service.MyHomeAnnouncementSourceMapper.MyHomeSupplyRowMappingData;
import com.toadzip.backend.ingest.mapping.service.MyHomeAnnouncementSupplyMatcher.MyHomeSupplyMatchResult;
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionScope;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AnnouncementSupplyMatchingService {
    private final MyHomeAnnouncementSourceReader sources;
    private final MyHomeAnnouncementSourceMapper mapper;
    private final MyHomeAnnouncementSupplyRowResolver resolver;
    private final MyHomeAnnouncementSupplyMatcher matcher;
    private final AnnouncementRepository announcements;
    private final MyHomeAnnouncementMappingWriter writer;
    private final HousingComplexRepository complexes;
    private final HousingTypeRepository types;

    public List<Row> rows(String identifier) {
        return supplyRows(identifier).stream().map(data -> {
            var match = matcher.match(data);
            Long complexId = null;
            Long typeId = null;
            if (match.complex() != null) {
                complexId = match.complex().getId();
            }
            if (match.housingType() != null) {
                typeId = match.housingType().getId();
            }
            return new Row(data.sourceSupplyRowIdentifier(),
                    token(data),
                    data.sourceComplexName(), data.sourceHousingTypeName(), data.pnu(),
                    data.exclusiveArea(), data.supplyArea(), complexId, typeId, match.failureDetail());
        }).toList();
    }

    @Transactional
    public MyHomeAnnouncementMappingReport refine(String identifier, RefineRequest request) {
        // POST 인터셉터가 트랜잭션 종료까지 전역 수집 잠금을 유지한다. 외부 API는 호출하지 않는다.
        IngestExecutionScope.verifyHeld();
        var current = resolved(identifier);
        var sourceRows = current.data().supplyRows();
        if (request.rows().size() != sourceRows.size()) {
            throw new AdminDataConflictException("공급행 구성이 변경되었습니다. 다시 조회해 주세요.");
        }
        var selections = new LinkedHashMap<String, MyHomeSupplyMatchResult>();
        for (var selection : request.rows()) {
            var data = sourceRows.stream().filter(row -> row.sourceSupplyRowIdentifier()
                    .equals(selection.rowIdentifier())).findFirst()
                    .orElseThrow(() -> new InvalidIngestRequestException("해당 공고의 공급행이 아닙니다."));
            if (!token(data).equals(selection.token())) {
                throw new AdminDataConflictException("원천이 변경되었습니다. 다시 조회해 주세요.");
            }
            var match = matcher.matchSelected(data, selection.complexId(), selection.housingTypeId());
            if (match.failure() != null) {
                throw new InvalidIngestRequestException("공급행 " + selection.rowIdentifier() + ": "
                        + match.failureDetail());
            }
            if (selections.putIfAbsent(selection.rowIdentifier(), match) != null) {
                throw new InvalidIngestRequestException("같은 공급행을 중복 선택할 수 없습니다.");
            }
        }
        var existing = announcements.findBySourceAnnouncementIdentifierForUpdate(identifier).orElse(null);
        if (existing != null && existing.isAdminDeleted()) {
            throw new InvalidIngestRequestException("삭제된 공고입니다. 공고 관리에서 먼저 복구해 주세요.");
        }
        if (existing != null && existing.isAdminModified() && existing.getProvider() != current.data().provider()) {
            throw new InvalidIngestRequestException("관리자가 변경한 공고 기관이 원천과 다릅니다. 공고 관리에서 확인해 주세요.");
        }
        var previous = announcements.findBySourceAnnouncementIdentifier(
                current.data().previousSourceAnnouncementIdentifier()).orElse(null);
        if (current.data().previousSourceAnnouncementIdentifier() != null && previous == null) {
            throw new InvalidIngestRequestException("이전 공고를 먼저 등록해 주세요.");
        }
        try {
            return writer.writeSelected(current, previous, selections).report();
        }
        catch (MyHomeAnnouncementSourceMapper.MyHomeAnnouncementMappingRejectedException exception) {
            throw new InvalidIngestRequestException("정제 실패: " + exception.getMessage());
        }
    }

    private String token(MyHomeSupplyRowMappingData data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(data.matchingIdentity().getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", exception);
        }
    }

    public List<ComplexOption> complexes(String query) {
        if (query == null || query.isBlank() || query.length() > 100) {
            throw new InvalidIngestRequestException("단지 이름 또는 주소를 1~100자로 입력해 주세요.");
        }
        String keyword = query.strip().toLowerCase(java.util.Locale.ROOT)
                .replace("!", "!!").replace("%", "!%").replace("_", "!_");
        return complexes.searchAdmin("%" + keyword + "%", query.strip(), "", "", "", "", "",
                false, false, PageRequest.of(0, 20)).stream()
                .map(value -> new ComplexOption(value.getId(), value.getName(),
                        value.getAddress().getRoadAddress(), value.getSupplyType())).toList();
    }

    public List<HousingTypeOption> housingTypes(long complexId) {
        var complex = complexes.findById(complexId)
                .filter(value -> !value.isAdminDeleted())
                .orElseThrow(() -> new InvalidIngestRequestException("선택한 단지가 없습니다."));
        return types.findAllByHousingComplex(complex).stream()
                .sorted(java.util.Comparator.comparing(com.toadzip.backend.housing.domain.HousingType::getId))
                .map(value -> new HousingTypeOption(value.getId(), value.getName(),
                        value.getExclusiveArea(), value.getSupplyArea())).toList();
    }

    private List<MyHomeSupplyRowMappingData> supplyRows(String identifier) {
        return resolved(identifier).data().supplyRows();
    }

    private MyHomeAnnouncementSupplyRowResolver.ResolvedAnnouncement resolved(String identifier) {
        if (identifier == null || identifier.isBlank() || identifier.length() > 100) {
            throw new InvalidIngestRequestException("공고 ID가 올바르지 않습니다.");
        }
        var current = sources.findAllByPblancIdOrderByIdAsc(identifier);
        if (current.isEmpty()) {
            throw new InvalidIngestRequestException("공고 원천이 없습니다. 먼저 공고를 수집해 주세요.");
        }
        try {
            return resolver.resolve(mapper.map(current));
        }
        catch (MyHomeAnnouncementSourceMapper.MyHomeAnnouncementMappingRejectedException exception) {
            throw new InvalidIngestRequestException(exception.getMessage());
        }
    }
}
