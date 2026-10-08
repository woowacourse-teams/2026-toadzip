package com.toadzip.backend.ingest.mapping.service;

import com.toadzip.backend.admin.domain.AdminDataChange;
import com.toadzip.backend.admin.repository.AdminDataChangeRepository;
import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.repository.AnnouncementRepository;
import com.toadzip.backend.announcement.repository.SupplyRowRepository;
import com.toadzip.backend.housing.domain.Address;
import com.toadzip.backend.housing.domain.AgencyCode;
import com.toadzip.backend.housing.repository.HousingComplexRepository;
import com.toadzip.backend.housing.repository.HousingTypeRepository;
import com.toadzip.backend.ingest.correction.dto.IngestCorrectionRequest;
import com.toadzip.backend.ingest.exception.exception.InvalidIngestRequestException;
import com.toadzip.backend.ingest.location.service.RoadAddressGeocodingService;
import com.toadzip.backend.ingest.mapping.repository.MyHomeComplexMappingFailureStore;
import com.toadzip.backend.ingest.mapping.repository.MyHomeAnnouncementMappingFailureStore;
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionScope;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** 최종 데이터와 대상 실패 이력을 같은 트랜잭션으로 저장한다. 외부 API는 호출하지 않는다. */
@Service
@RequiredArgsConstructor
public class IngestCorrectionProcessor {
    private final IngestCorrectionSources sources;
    private final MyHomeComplexSourceMapper complexMapper;
    private final MyHomeComplexMappingWriter complexWriter;
    private final MyHomeAnnouncementSourceMapper announcementMapper;
    private final MyHomeAnnouncementSupplyRowResolver resolver;
    private final MyHomeAnnouncementMappingWriter announcementWriter;
    private final RoadAddressGeocodingService geocoding;
    private final HousingComplexRepository complexes;
    private final HousingTypeRepository types;
    private final AnnouncementRepository announcements;
    private final SupplyRowRepository supplies;
    private final MyHomeComplexMappingFailureStore complexFailures;
    private final MyHomeAnnouncementMappingFailureStore announcementFailures;
    private final AdminDataChangeRepository history;
    private final ObjectMapper json;

    @Transactional
    public long refine(String domain, String identifier, IngestCorrectionRequest request, String actor) {
        IngestExecutionScope.verifyHeld();
        long result;
        try {
            result = refineTarget(domain, identifier, request);
        }
        catch (MyHomeComplexSourceMapper.MyHomeComplexMappingRejectedException
                | MyHomeAnnouncementSourceMapper.MyHomeAnnouncementMappingRejectedException
                | com.toadzip.backend.ingest.location.exception.RoadAddressGeocodingException
                | IllegalArgumentException exception) {
            throw new InvalidIngestRequestException("보완 후 정제 실패: " + exception.getMessage());
        }
        String resource = "ANNOUNCEMENT";
        if (domain.equals("complex")) {
            resource = "COMPLEX";
        }
        history.save(new AdminDataChange(resource, result, "INGEST_CORRECTION", actor,
                "{}", json.writeValueAsString(request)));
        IngestExecutionScope.verifyHeld();
        return result;
    }

    private long refineTarget(String domain, String identifier, IngestCorrectionRequest request) {
        if (domain.equals("complex")) {
            return refineComplex(identifier, request);
        }
        return refineAnnouncement(identifier, request);
    }

    private long refineComplex(String identifier, IngestCorrectionRequest request) {
        var rows = sources.complexes(identifier, request.rows());
        if (rows.isEmpty()) {
            throw new InvalidIngestRequestException("단지 원천이 없습니다. 단지 수집을 먼저 실행해 주세요.");
        }
        var data = complexMapper.map(identifier, rows);
        Address address;
        if (request.latitude() == null) {
            address = data.address().resolve(geocoding.geocode(data.address().sourceRoadAddress()));
        }
        else {
            var sourceAddress = data.address();
            address = Address.create(sourceAddress.sourceRoadAddress(), sourceAddress.pnu(),
                    sourceAddress.legalDongCode(), sourceAddress.provinceCode(), sourceAddress.cityCountyDistrictCode(),
                    request.latitude(), request.longitude());
        }
        complexWriter.writeCorrection(data, address);
        var complex = complexes.findBySourceComplexIdentifier(identifier).orElseThrow();
        complex.reviseByAdmin(complex);
        types.findAllByHousingComplex(complex).forEach(type -> type.protectAdminCorrection());
        complexFailures.resolveForComplex(identifier);
        complexes.flush();
        return complex.getId();
    }

    private long refineAnnouncement(String identifier, IngestCorrectionRequest request) {
        var rows = sources.announcements(identifier, request.rows());
        if (rows.isEmpty()) {
            throw new InvalidIngestRequestException("공고 원천이 없습니다. 공고 수집을 먼저 실행해 주세요.");
        }
        var resolved = resolver.resolve(announcementMapper.map(rows));
        if (resolved.data().provider() == AgencyCode.LH && resolved.supplies().isEmpty()) {
            throw new InvalidIngestRequestException("LH 공급 원천이 없습니다. 공고 수집을 먼저 다시 실행해 주세요.");
        }
        Announcement previous = null;
        String previousId = resolved.data().previousSourceAnnouncementIdentifier();
        if (previousId != null) {
            previous = announcements.findBySourceAnnouncementIdentifier(previousId)
                    .orElseThrow(() -> new InvalidIngestRequestException("이전 공고를 먼저 등록해 주세요: " + previousId));
        }
        var result = announcementWriter.writeCorrection(resolved, previous);
        if (!result.failures().isEmpty()) {
            throw new InvalidIngestRequestException("공급행 매칭 실패: " + result.failures().getFirst().detail()
                    + " 단지 탭에서 필요한 단지·주택형을 먼저 등록해 주세요.");
        }
        var announcement = announcements.findBySourceAnnouncementIdentifier(identifier).orElseThrow();
        announcement.reviseByAdmin(announcement);
        supplies.findAllByAnnouncement(announcement).forEach(row -> row.reviseByAdmin(
                row.getHousingComplex(), row.getHousingType(), row.getSourceComplexName(),
                row.getSourceHousingTypeName(), row.getSupplyPnu(),
                row.getExpectedMoveInMonth(), row.getSupplyCategory(), row.getTotalSupplyHouseholdCount()));
        announcementFailures.reconcileForAnnouncement(identifier, List.of(), null);
        announcements.flush();
        return announcement.getId();
    }
}
