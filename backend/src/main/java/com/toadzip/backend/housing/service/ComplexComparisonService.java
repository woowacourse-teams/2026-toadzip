package com.toadzip.backend.housing.service;

import com.toadzip.backend.admin.domain.AdminDataChange;
import com.toadzip.backend.admin.exception.AdminDataConflictException;
import com.toadzip.backend.admin.repository.AdminDataChangeRepository;
import com.toadzip.backend.housing.domain.Address;
import com.toadzip.backend.housing.domain.AgencyCode;
import com.toadzip.backend.housing.domain.ComplexComparisonLink;
import com.toadzip.backend.housing.domain.ComplexComparisonSourceType;
import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.domain.RentalType;
import com.toadzip.backend.housing.dto.request.AdminHousingComplexCreateRequest.AddressRequest;
import com.toadzip.backend.housing.dto.request.AdminHousingComplexUpdateRequest;
import com.toadzip.backend.housing.dto.request.ComplexComparisonLinkRequest;
import com.toadzip.backend.housing.dto.request.ComplexComparisonUpdateRequest;
import com.toadzip.backend.housing.dto.response.ComplexComparisonResponse;
import com.toadzip.backend.housing.dto.response.ComplexComparisonResponse.Source;
import com.toadzip.backend.housing.exception.AdminHousingComplexNotFoundException;
import com.toadzip.backend.housing.exception.InvalidRegionCodeException;
import com.toadzip.backend.housing.repository.ComplexComparisonLinkRepository;
import com.toadzip.backend.housing.repository.HousingComplexRepository;
import com.toadzip.backend.ingest.collection.myhome.complex.repository.MyHomeComplexSourceReader;
import com.toadzip.backend.ingest.mapping.repository.MyHomeComplexLinkRepository;
import com.toadzip.backend.ingest.source.dto.IngestSourceCategory;
import com.toadzip.backend.ingest.source.repository.IngestSourceQueryRepository;
import com.toadzip.backend.region.repository.RegionCodeResolver;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class ComplexComparisonService {
    private final HousingComplexRepository complexes;
    private final ComplexComparisonLinkRepository comparisonLinks;
    private final MyHomeComplexLinkRepository mappingLinks;
    private final MyHomeComplexSourceReader myHome;
    private final IngestSourceQueryRepository sources;
    private final AdminDataChangeRepository changes;
    private final RegionCodeResolver regions;
    private final ObjectMapper json;
    private final Clock clock;

    public ComplexComparisonService(HousingComplexRepository complexes, ComplexComparisonLinkRepository comparisonLinks,
            MyHomeComplexLinkRepository mappingLinks, MyHomeComplexSourceReader myHome,
            IngestSourceQueryRepository sources, AdminDataChangeRepository changes, RegionCodeResolver regions,
            ObjectMapper json, Clock clock) {
        this.complexes = complexes;
        this.comparisonLinks = comparisonLinks;
        this.mappingLinks = mappingLinks;
        this.myHome = myHome;
        this.sources = sources;
        this.changes = changes;
        this.regions = regions;
        this.json = json;
        this.clock = clock;
    }

    public ComplexComparisonResponse detail(long id) {
        return response(complexes.findById(id).orElseThrow(AdminHousingComplexNotFoundException::new));
    }

    @Transactional
    public ComplexComparisonResponse link(long id, ComplexComparisonLinkRequest request, String actor) {
        var complex = writable(id);
        var before = checked(complex, request.snapshotToken());
        if (before.sources().stream().anyMatch(source -> source.category() == request.category()
                && source.sourceKey().equals(request.sourceKey()))) {
            throw new AdminDataConflictException("이미 연결된 원천입니다. 다시 조회해 주세요.");
        }
        if (comparisonLinks.findAllByHousingComplexIdOrderById(id).size() >= 20) {
            throw new IllegalArgumentException("비교 원천은 최대 20개까지 연결할 수 있습니다.");
        }
        if (sources.findAllByKey(category(request.category()), request.sourceKey()).isEmpty()) {
            throw new AdminDataConflictException("원천이 변경되었거나 없습니다. 다시 검색해 주세요.");
        }
        comparisonLinks.saveAndFlush(new ComplexComparisonLink(id, request.category(), request.sourceKey(),
                request.evidenceNote(), actor, clock.instant()));
        var after = response(complex);
        record(id, "LINK_COMPARISON_SOURCE", actor, Map.of("sources", before.sources()), Map.of(
                "sources", after.sources(), "note", request.evidenceNote()));
        return after;
    }

    @Transactional
    public ComplexComparisonResponse unlink(long id, ComplexComparisonLinkRequest request, String actor) {
        var complex = writable(id);
        var before = checked(complex, request.snapshotToken());
        var link = comparisonLinks.findAllByHousingComplexIdOrderById(id).stream()
                .filter(item -> item.getCategory() == request.category()
                        && item.getSourceKey().equals(request.sourceKey())).findFirst()
                .orElseThrow(() -> new AdminDataConflictException("직접 연결한 비교 원천만 해제할 수 있습니다."));
        comparisonLinks.delete(link);
        comparisonLinks.flush();
        var after = response(complex);
        record(id, "UNLINK_COMPARISON_SOURCE", actor, Map.of("sources", before.sources()),
                Map.of("sources", after.sources(), "note", request.evidenceNote()));
        return after;
    }

    @Transactional
    public ComplexComparisonResponse update(long id, ComplexComparisonUpdateRequest request, String actor) {
        var complex = writable(id);
        var before = checked(complex, request.snapshotToken());
        var incoming = request.current();
        if (complex.getVersion() != incoming.version()) {
            throw new AdminDataConflictException("다른 작업에서 단지를 수정했습니다. 다시 조회해 주세요.");
        }
        if (incoming.documentEvidence() != null) {
            throw new IllegalArgumentException("PDF 근거는 PDF 보완 화면에서 확인해 주세요.");
        }
        var address = incoming.address();
        if (!sameAddress(before.current().address(), address) && !Boolean.TRUE.equals(request.addressConfirmed())) {
            throw new IllegalArgumentException("주소·PNU·지역 코드·좌표를 함께 확인해 주세요.");
        }
        if (regions.resolve(address.provinceCode(), address.cityCountyDistrictCode()).isEmpty()) {
            throw new InvalidRegionCodeException();
        }
        complex.reviseByAdmin(HousingComplex.create(incoming.name(), complex.getSourceComplexIdentifier(),
                incoming.rentalType().name(), Address.create(address.roadAddress(), address.pnu(),
                        address.legalDongCode(), address.provinceCode(), address.cityCountyDistrictCode(),
                        address.latitude(), address.longitude()), incoming.totalHouseholdCount(),
                incoming.agencyCode().name(), incoming.completionDate(), incoming.heatingType(),
                incoming.buildingType(), incoming.corridorType(), incoming.hasElevator(),
                incoming.totalParkingCount(), incoming.overviewImageUrl(), incoming.moveOutCountLastYear()));
        complexes.flush();
        var after = response(complex);
        Map<String, Object> recorded = new LinkedHashMap<>(json.convertValue(after.current(),
                new tools.jackson.core.type.TypeReference<Map<String, Object>>() { }));
        recorded.put("comparisonEvidence", Map.of("note", request.evidenceNote(), "sources", before.sources()));
        record(id, "UPDATE_COMPARED", actor, before.current(), recorded);
        return after;
    }

    private HousingComplex writable(long id) {
        var complex = complexes.findByIdForUpdate(id).orElseThrow(AdminHousingComplexNotFoundException::new);
        if (complex.isAdminDeleted()) {
            throw new AdminDataConflictException("휴지통에서 복구한 뒤 수정해 주세요.");
        }
        return complex;
    }

    private ComplexComparisonResponse checked(HousingComplex complex, String token) {
        var response = response(complex);
        if (!response.snapshotToken().equals(token)) {
            throw new AdminDataConflictException("등록값 또는 비교 원천이 변경되었습니다. 다시 조회해 주세요.");
        }
        return response;
    }

    private ComplexComparisonResponse response(HousingComplex complex) {
        var current = current(complex);
        List<Source> evidence = new ArrayList<>(automaticSources(complex));
        comparisonLinks.findAllByHousingComplexIdOrderById(complex.getId())
                .forEach(link -> evidence.addAll(linkedSources(link)));
        return new ComplexComparisonResponse(token(current, evidence), current, List.copyOf(evidence));
    }

    private List<Source> linkedSources(ComplexComparisonLink link) {
        var rows = sources.findAllByKey(category(link.getCategory()), link.getSourceKey());
        if (rows.isEmpty()) {
            return List.of(new Source(link.getCategory(), link.getSourceKey(), false, link.getEvidenceNote(),
                    link.getActor(), link.getLinkedAt(), null));
        }
        return rows.stream().map(row -> new Source(link.getCategory(), link.getSourceKey(), false,
                link.getEvidenceNote(), link.getActor(), link.getLinkedAt(), row)).toList();
    }

    private List<Source> automaticSources(HousingComplex complex) {
        List<String> identifiers = mappingLinks.findAllByHousingComplexId(complex.getId()).stream()
                .map(link -> link.getSourceComplexIdentifier()).sorted().toList();
        if (identifiers.isEmpty()) {
            identifiers = List.of(complex.getSourceComplexIdentifier());
        }
        List<Source> result = new ArrayList<>();
        identifiers.forEach(identifier -> addAutomatic(identifier, result));
        return result;
    }

    private void addAutomatic(String identifier, List<Source> result) {
        if (!identifier.matches("[0-9]{1,18}:[A-Z0-9_]+")) {
            return;
        }
        String rentalCode = identifier.substring(identifier.indexOf(':') + 1);
        RentalType rental;
        try {
            rental = RentalType.valueOf(rentalCode);
        } catch (IllegalArgumentException unsupported) {
            return;
        }
        var rows = myHome.findAllByHsmpSnIn(List.of(Long.parseLong(identifier.substring(0, identifier.indexOf(':')))))
                .stream().filter(row -> matchesRental(row.getSuplyTyNm(), rental)).toList();
        rows.forEach(row -> sources.findById(IngestSourceCategory.MYHOME_COMPLEX, row.getId()).ifPresent(item ->
                result.add(new Source(ComplexComparisonSourceType.MYHOME_COMPLEX, item.sourceKey(), true,
                        "자동 정제 원천 " + identifier, null, null, item))));
        if (rows.isEmpty()) {
            result.add(new Source(ComplexComparisonSourceType.MYHOME_COMPLEX, identifier, true,
                    "자동 정제 원천을 현재 DB에서 찾지 못했습니다.", null, null, null));
        }
    }

    private boolean matchesRental(String value, RentalType rental) {
        if (rental == RentalType.PUBLIC_RENTAL_50Y) {
            return "50년임대".equals(value);
        }
        return rental.legacyStoredValue().equals(value);
    }

    private IngestSourceCategory category(ComplexComparisonSourceType type) {
        return IngestSourceCategory.valueOf(type.name());
    }

    private AdminHousingComplexUpdateRequest current(HousingComplex complex) {
        var address = complex.getAddress();
        return new AdminHousingComplexUpdateRequest(complex.getVersion(), complex.getName(),
                RentalType.fromStoredValue(complex.getSupplyType()), AgencyCode.fromStoredValue(complex.getProvider()),
                new AddressRequest(address.getRoadAddress(), address.getPnu(), address.getLegalDongCode(),
                        address.getProvinceCode(), address.getCityCountyDistrictCode(),
                        address.getLatitude().stripTrailingZeros(), address.getLongitude().stripTrailingZeros()),
                complex.getTotalHouseholdCount(), complex.getCompletionDate(),
                complex.getHeatingType(), complex.getHousingType(), complex.getCorridorType(),
                complex.getElevatorInstalled(), complex.getParkingSpaceCount(), complex.getImageUrl(),
                complex.getRecentOneYearMoveOutCount());
    }

    private String token(Object current, List<Source> evidence) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                    json.writeValueAsString(List.of(current, evidence)).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private boolean sameAddress(AddressRequest before, AddressRequest after) {
        return before.roadAddress().equals(after.roadAddress()) && before.pnu().equals(after.pnu())
                && before.legalDongCode().equals(after.legalDongCode())
                && before.provinceCode().equals(after.provinceCode())
                && before.cityCountyDistrictCode().equals(after.cityCountyDistrictCode())
                && before.latitude().compareTo(after.latitude()) == 0
                && before.longitude().compareTo(after.longitude()) == 0;
    }

    private void record(long id, String action, String actor, Object before, Object after) {
        changes.save(new AdminDataChange("COMPLEX", id, action, actor,
                json.writeValueAsString(before), json.writeValueAsString(after)));
    }
}
