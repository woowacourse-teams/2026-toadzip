package com.toadzip.backend.announcement.service;

import com.toadzip.backend.admin.domain.AdminDataChange;
import com.toadzip.backend.admin.repository.AdminDataChangeRepository;
import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.AnnouncementComplex;
import com.toadzip.backend.announcement.dto.correction.SupplySheetInput;
import com.toadzip.backend.announcement.dto.correction.SupplySheetView;
import com.toadzip.backend.announcement.repository.AnnouncementComplexRepository;
import com.toadzip.backend.housing.repository.HousingComplexRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * PDF 보정이 연결한 기존 단지의 이름을 공고의 단지명으로 맞춘다(사용자 결정: 단지 정보는 공고가 가장 정확하다).
 * 믿을 만한 단지명은 기관 원천(LH·GH 공고 상세)이 주소와 함께 알려 준 공고 단지의 이름뿐이다. 공고문 추출의 단지명은 공급표 칸의 줄임
 * 표기('증평송산5')나 띄어 읽힌 글자일 수 있어 쓰지 않는다. 관리자가 고치거나 등록한 단지의 이름은 바꾸지 않는다.
 * 이 단지에 이어진 공고 단지(이번 공고의 저장할 공급표와 휴지통이 아닌 다른 공고)의 기관 원천 단지명이 둘 이상이면 공고마다 이름이
 * 흔들리므로 바꾸지 않는다. LH·GH 공고 정제는 단지 이름을 바꾸지 않는다. 막아 둔 마이홈 단지 동기화(단지 정제)를 다시 돌리면 관리자
 * 수정이 없는 단지의 이름은 마이홈 값으로 돌아간다.
 */
@Component
@RequiredArgsConstructor
class AnnouncementComplexNames {
    private final AnnouncementComplexRepository announcementComplexes;
    private final HousingComplexRepository complexes;
    private final AdminDataChangeRepository changes;
    private final ObjectMapper json;

    record Rename(long housingComplexId, String before, String after) { }

    /** 저장할 공급표(after)의 주택형이 잇는 공고 단지와 단지로 바꿀 이름을 정한다. 주택형이 고른 공고 단지가 없으면 이름으로 찾는다. */
    List<Rename> plan(Announcement announcement, SupplySheetView after) {
        var own = announcementComplexes.findAllByAnnouncementOrderByIdAsc(announcement);
        Map<Long, Set<String>> names = new TreeMap<>();
        for (var type : after.housingTypes()) {
            if (type.housingComplexId() == null) {
                continue;
            }
            sourceOf(own, type).filter(AnnouncementComplexNames::announces).ifPresent(source -> names
                    .computeIfAbsent(type.housingComplexId(), ignored -> new LinkedHashSet<>())
                    .add(source.getSourceComplexName().strip()));
        }
        var result = new ArrayList<Rename>();
        names.forEach((complexId, announced) -> {
            announcementComplexes.findAllByHousingComplexIdAndAnnouncementAdminDeletedFalse(complexId).stream()
                    .filter(other -> !other.getAnnouncement().getId().equals(announcement.getId()))
                    .filter(AnnouncementComplexNames::announces)
                    .forEach(other -> announced.add(other.getSourceComplexName().strip()));
            if (announced.size() != 1) {
                return;
            }
            String name = announced.iterator().next();
            complexes.findById(complexId).filter(complex -> complex.acceptsAnnouncementName()
                    && !complex.getName().equals(name))
                    .ifPresent(complex -> result.add(new Rename(complexId, complex.getName(), name)));
        });
        return result;
    }

    /** 이름의 근거가 되는 공고 단지: 기관 원천이 주소와 함께 알려 줬고 원천에서 빠지지 않았다. */
    private static boolean announces(AnnouncementComplex complex) {
        return complex.namedBySource() && complex.isPresentInSource();
    }

    private static Optional<AnnouncementComplex> sourceOf(List<AnnouncementComplex> own, SupplySheetView.HousingType type) {
        if (type.announcementComplexId() != null) {
            return own.stream().filter(complex -> complex.getId().equals(type.announcementComplexId())).findFirst();
        }
        String name = type.complexName() == null ? "" : type.complexName().strip();
        return own.stream().filter(complex -> complex.getSourceComplexName().strip().equals(name)).findFirst();
    }

    List<String> warnings(List<Rename> renames) {
        return renames.stream().map(rename -> "단지 이름 '" + rename.before() + "'을(를) 공고의 단지명 '" + rename.after()
                + "'(으)로 바꿉니다.").toList();
    }

    /**
     * 이름을 바꿀 수 있는 단지(요청 공급표가 고른 단지)를 id 순서로 잠근다. 미리보기를 다시 계산하기 전에 잠가야 그 사이 커밋된
     * 관리자 단지 수정을 읽고, 공급행 저장이 단지 행에 거는 외래 키 잠금보다 먼저 잡는다.
     */
    void lock(SupplySheetInput sheet) {
        var ids = new TreeSet<Long>();
        sheet.housingTypes().stream().map(SupplySheetInput.HousingType::housingComplexId).filter(Objects::nonNull)
                .forEach(ids::add);
        ids.forEach(complexes::findByIdForUpdate);
    }

    void apply(List<Rename> renames, long announcementId, String actor) {
        for (var rename : renames) {
            var complex = complexes.findByIdForUpdate(rename.housingComplexId()).orElseThrow();
            if (!complex.renameFromAnnouncement(rename.after())) {
                continue;
            }
            var after = new LinkedHashMap<String, Object>();
            after.put("name", rename.after());
            after.put("announcementId", announcementId);
            changes.save(new AdminDataChange("COMPLEX", complex.getId(), "RENAME_FROM_ANNOUNCEMENT", actor,
                    json.writeValueAsString(Map.of("name", rename.before())), json.writeValueAsString(after)));
        }
    }
}
