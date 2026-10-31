package com.toadzip.backend.ingest.mapping.service;

import com.toadzip.backend.housing.repository.HousingComplexRepository;
import com.toadzip.backend.housing.repository.HousingTypeRepository;
import com.toadzip.backend.ingest.exception.exception.InvalidIngestRequestException;
import com.toadzip.backend.ingest.mapping.dto.AnnouncementSupplyMatching.ComplexOption;
import com.toadzip.backend.ingest.mapping.dto.AnnouncementSupplyMatching.HousingTypeArea;
import com.toadzip.backend.ingest.mapping.dto.AnnouncementSupplyMatching.HousingTypeOption;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 관리자가 공급행을 연결할 단지·주택형 후보를 찾는다. 저장하지 않는다. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AnnouncementSupplyCandidateService {
    private final HousingComplexRepository complexes;
    private final HousingTypeRepository types;
    private final SupplyMatchingFingerprint fingerprints;

    public List<ComplexOption> complexes(String query) {
        return complexes(query, "");
    }

    /** 원천 PNU가 오면 이름이 달라도 같은 필지의 단지를 후보에 넣는다. 원천 단지명과 등록 단지명이 다른 본 단지를 놓치지 않기 위해서다. */
    public List<ComplexOption> complexes(String query, String pnu) {
        if (query == null || query.isBlank() || query.length() > 100) {
            throw new InvalidIngestRequestException("단지 이름 또는 주소를 1~100자로 입력해 주세요.");
        }
        String parcel = pnu == null ? "" : pnu.strip();
        if (!parcel.isEmpty() && !parcel.matches("\\d{1,30}")) {
            throw new InvalidIngestRequestException("PNU는 숫자로 입력해 주세요.");
        }
        String keyword = query.strip().toLowerCase(java.util.Locale.ROOT)
                .replace("!", "!!").replace("%", "!%").replace("_", "!_");
        var merged = new LinkedHashMap<Long, com.toadzip.backend.housing.domain.HousingComplex>();
        if (!parcel.isEmpty()) {
            complexes.findAllByPnu(parcel).forEach(value -> merged.put(value.getId(), value));
        }
        complexes.searchAdmin("%" + keyword + "%", query.strip(), "", "", "", "", "",
                false, false, "", PageRequest.of(0, 20)).getContent()
                .forEach(value -> merged.putIfAbsent(value.getId(), value));
        var found = List.copyOf(merged.values());
        // 이름·주소가 같은 단지가 원천에서 여러 개로 나뉘어 있어 PNU·세대수와 주택형 면적으로 구분하게 한다.
        var typesByComplex = found.isEmpty() ? Map.<Long, List<HousingTypeArea>>of()
                : types.findAllByHousingComplexIdIn(found.stream().map(value -> value.getId()).toList()).stream()
                        .sorted(Comparator.comparing(com.toadzip.backend.housing.domain.HousingType::getId))
                        .collect(java.util.stream.Collectors.groupingBy(value -> value.getHousingComplex().getId(),
                                java.util.stream.Collectors.mapping(value -> new HousingTypeArea(value.getId(),
                                        value.getName(), value.getExclusiveArea()), java.util.stream.Collectors.toList())));
        return found.stream()
                .map(value -> new ComplexOption(value.getId(), value.getName(),
                        value.getAddress().getRoadAddress(), value.getSupplyType(), value.getAddress().getPnu(),
                        value.getTotalHouseholdCount(), typesByComplex.getOrDefault(value.getId(), List.of()))).toList();
    }

    public List<HousingTypeOption> housingTypes(long complexId) {
        var complex = complexes.findById(complexId)
                .filter(value -> !value.isAdminDeleted())
                .orElseThrow(() -> new InvalidIngestRequestException("선택한 단지가 없습니다."));
        return types.findAllByHousingComplex(complex).stream()
                .sorted(java.util.Comparator.comparing(com.toadzip.backend.housing.domain.HousingType::getId))
                .map(value -> new HousingTypeOption(value.getId(), value.getName(),
                        value.getExclusiveArea(), value.getSupplyArea(), value.getTotalHouseholdCount(),
                        fingerprints.target(value))).toList();
    }
}
