package com.toadzip.backend.ingest.gh.refinement.service;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.AnnouncementComplex;
import com.toadzip.backend.announcement.domain.ComplexLinkMethod;
import com.toadzip.backend.announcement.repository.AnnouncementComplexRepository;
import com.toadzip.backend.housing.domain.RentalType;
import com.toadzip.backend.housing.repository.HousingComplexRepository;
import com.toadzip.backend.ingest.gh.refinement.domain.GhLotAddress;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * GH 지구를 공고 단지로 맞추고, 새로 만든 공고 단지만 제품 단지 하나를 미리 고른다(AUTO). 이미 붙은 연결은 다시 계산하지 않는다.
 * 공고 단지의 주소 칸에는 지구 소재지(지번)를 그대로 두므로 도로명 주소로 쓰지 않는다. 지구 모집호수는 단지 총 세대수가 아니라 비운다.
 */
@Component
@RequiredArgsConstructor
class GhAnnouncementComplexLinker {

    private static final String ACTOR = "GH 공고 정제";

    private final AnnouncementComplexRepository announcementComplexes;
    private final HousingComplexRepository housingComplexes;
    private final Clock clock;

    /** 이 공고의 공고 단지를 지구 순서대로 돌려준다. 원천에서 빠진 지구의 공고 단지는 빠진 것으로 표시한다. */
    Map<String, AnnouncementComplex> observe(Announcement announcement, JsonNode districts,
            Optional<RentalType> supplyType, GhComplexIndex index) {
        Map<String, AnnouncementComplex> stored = announcementComplexes.findAllByAnnouncementOrderByIdAsc(announcement)
                .stream().collect(Collectors.toMap(AnnouncementComplex::getSourceComplexName, Function.identity(),
                        (first, second) -> first, LinkedHashMap::new));
        Map<String, AnnouncementComplex> result = new LinkedHashMap<>();
        for (JsonNode district : districts) {
            String name = district.path("name").asText();
            String location = district.path("address").asText(null);
            AnnouncementComplex complex = stored.remove(name);
            if (complex == null) {
                complex = announcementComplexes.save(AnnouncementComplex.observe(announcement, name, location, null));
                link(complex, location, supplyType, index);
            }
            else {
                complex.observeAgain(location, null);
            }
            result.putIfAbsent(name, complex);
        }
        stored.values().forEach(AnnouncementComplex::markAbsentFromSource);
        return result;
    }

    private void link(AnnouncementComplex complex, String location, Optional<RentalType> supplyType,
            GhComplexIndex index) {
        Optional<GhLotAddress> lot = GhLotAddress.parse(location);
        if (lot.isEmpty() || supplyType.isEmpty()) {
            return;
        }
        index.soleCandidate(lot.get(), supplyType.get()).flatMap(housingComplexes::findById)
                .filter(found -> !found.isAdminDeleted())
                .ifPresent(found -> complex.linkBySource(found, ComplexLinkMethod.AUTO, ACTOR, clock.instant()));
    }
}
