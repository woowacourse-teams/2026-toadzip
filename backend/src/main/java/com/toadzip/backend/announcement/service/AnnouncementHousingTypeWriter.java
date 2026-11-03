package com.toadzip.backend.announcement.service;

import com.toadzip.backend.admin.domain.AdminDataChange;
import com.toadzip.backend.admin.exception.AdminDataConflictException;
import com.toadzip.backend.admin.repository.AdminDataChangeRepository;
import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.SupplyRow;
import com.toadzip.backend.announcement.dto.correction.AnnouncementJsonCorrection.HousingTypeChange;
import com.toadzip.backend.announcement.dto.correction.AnnouncementJsonCorrection.HousingTypeValue;
import com.toadzip.backend.announcement.dto.correction.AnnouncementJsonCorrection.TypeValues;
import com.toadzip.backend.announcement.dto.correction.SupplyCorrection.NewType;
import com.toadzip.backend.announcement.dto.correction.SupplyCorrection.Row;
import com.toadzip.backend.housing.domain.HousingType;
import com.toadzip.backend.housing.domain.HousingType.AnnouncementValues;
import com.toadzip.backend.housing.repository.HousingComplexRepository;
import com.toadzip.backend.housing.repository.HousingTypeRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * PDF 보정 저장에서 공고문의 주택형 이름·공급면적·세대수와 기본 보증금·월 임대료를 공급행이 고른 주택형에 반영한다.
 * 관리자가 직접 고친 필드와 더 이른 공고 값은 주택형이 걸러 낸다({@link HousingType#afterAnnouncement}).
 */
@Service
@RequiredArgsConstructor
public class AnnouncementHousingTypeWriter {
    private static final ZoneId POSTED_DATE_ZONE = ZoneId.of("Asia/Seoul");

    private final HousingTypeRepository types;
    private final HousingComplexRepository complexes;
    private final AdminDataChangeRepository changes;
    private final ObjectMapper json;

    /**
     * 저장하면 바뀌는 주택형 값. planned는 저장할 공급행이고, 공고 값은 제외하지 않은 행이 주택형을 정했을 때만 받는다.
     * 관리자 수정값이나 더 최근 공고 값을 유지하는 필드는 warnings에 남긴다.
     */
    public List<HousingTypeChange> plan(LocalDate postedDate, List<Row> planned, List<HousingTypeValue> values,
            List<String> warnings) {
        var postedAt = postedAt(postedDate);
        List<HousingTypeChange> result = new ArrayList<>();
        for (var target : targets(planned, values, warnings)) {
            var row = target.row();
            var incoming = target.values();
            if (target.housingTypeId() != null) {
                var type = types.findById(target.housingTypeId())
                        .orElseThrow(() -> new AdminDataConflictException("연결할 주택형을 찾을 수 없습니다."));
                if (type.getHousingComplex().isAdminDeleted()) {
                    throw new IllegalArgumentException(row.typeName() + ": 휴지통 단지의 주택형에는 공고 값을 반영할 수 없습니다.");
                }
                var after = type.afterAnnouncement(incoming, postedAt);
                warnings.addAll(kept(type, incoming, after, postedAt));
                if (!same(values(type.announcementValues()), values(after))) {
                    result.add(new HousingTypeChange(row.key(), type.getId(), type.getName(),
                            values(type.announcementValues()), values(after)));
                }
                continue;
            }
            var created = target.created();
            result.add(new HousingTypeChange(row.key(), null, created.name(), null,
                    new TypeValues(firstPresent(incoming.name(), created.name()),
                            firstPresent(incoming.supplyArea(), created.supplyArea()), incoming.householdCount(),
                            incoming.basicDeposit(), incoming.basicMonthlyRent())));
        }
        return List.copyOf(result);
    }

    /**
     * 미리보기한 공고 값을 반영한다. 관리자 주택형 수정과 엇갈리지 않도록 단지를 id 순서로 잠그고, 값이 바뀐 주택형은
     * 단지 이력에 남기며 단지 버전을 올려 열려 있던 관리자 수정 화면이 공고 값을 덮지 않게 한다.
     * written은 이 저장에서 새로 만든 주택형을 찾을 공급행이다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void apply(Announcement announcement, LocalDate postedDate, List<Row> planned, List<HousingTypeValue> values,
            Map<String, SupplyRow> written, String actor) {
        Map<HousingType, AnnouncementValues> targets = new LinkedHashMap<>();
        for (var target : targets(planned, values, new ArrayList<>())) {
            var type = target.housingTypeId() != null ? types.findById(target.housingTypeId()).orElseThrow()
                    : written.get(target.row().key()).getHousingTypes().stream()
                            .filter(linked -> linked.getExclusiveArea().compareTo(target.created().exclusiveArea()) == 0)
                            .findFirst().orElseThrow();
            targets.put(type, target.values());
        }
        var complexIds = new TreeSet<Long>();
        targets.keySet().forEach(type -> complexIds.add(type.getHousingComplex().getId()));
        var locked = complexIds.stream().map(id -> complexes.findByIdForUpdate(id).orElseThrow())
                .collect(Collectors.toMap(complex -> complex.getId(), Function.identity()));
        var postedAt = postedAt(postedDate);
        for (var target : targets.entrySet()) {
            var type = target.getKey();
            String before = json.writeValueAsString(data(announcement, postedDate, type));
            if (!type.observeAnnouncement(target.getValue(), postedAt)) { continue; }
            var complex = locked.get(type.getHousingComplex().getId());
            complex.noteHousingTypeRevisionByAdmin();
            types.flush();
            changes.save(new AdminDataChange("COMPLEX", complex.getId(), "UPDATE_HOUSING_TYPE_FROM_PDF",
                    actor, before, json.writeValueAsString(data(announcement, postedDate, type))));
        }
    }

    /**
     * 공고 값을 보낸 저장이면 요청 공급행이 가리키는 단지를 id 순서로 먼저 잠근다. 미리보기를 다시 계산하기 전에 잠가야
     * 그 사이 커밋된 관리자 주택형 수정을 읽어 토큰이 달라지고, 오래 읽은 주택형으로 그 수정을 덮지 않는다.
     * 공급행 저장이 단지 행에 외래 키 공유 잠금을 건 뒤에 잠그면 같은 단지를 저장하는 다른 보정과 서로 기다릴 수도 있다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockComplexes(List<Row> rows, List<HousingTypeValue> values) {
        if (values.isEmpty()) { return; }
        var complexIds = new TreeSet<Long>();
        rows.stream().map(Row::housingComplexId).filter(Objects::nonNull).forEach(complexIds::add);
        var typeIds = rows.stream().map(Row::housingTypeId).filter(Objects::nonNull).toList();
        if (!typeIds.isEmpty()) { complexIds.addAll(types.findComplexIdsByIdIn(typeIds)); }
        complexIds.forEach(complexes::findByIdForUpdate);
    }

    /** 저장하면 반영한 공고 게시 시각이 앞당겨지는 기존 주택형이 있으면 true다. 값이 같아도 저장할 이유가 된다. */
    public boolean advancesObservation(LocalDate postedDate, List<Row> planned, List<HousingTypeValue> values) {
        var postedAt = postedAt(postedDate);
        return targets(planned, values, new ArrayList<>()).stream()
                .map(Target::housingTypeId).filter(Objects::nonNull)
                .map(id -> types.findById(id).orElseThrow())
                .anyMatch(type -> type.getAnnouncementObservedAt() == null || postedAt.isAfter(type.getAnnouncementObservedAt()));
    }

    /**
     * 주택형 하나에 반영할 공고 값과 그 값을 처음 보낸 공급행. 주택형은 그 행의 대표나 함께 모집하는 주택형이고,
     * housingTypeId가 없으면 이 저장에서 만드는 created다.
     */
    private record Target(Row row, Long housingTypeId, NewType created, AnnouncementValues values) {
        String typeKey() { return housingTypeId != null ? "type:" + housingTypeId : "new:" + row.newHousingTypeKey(created); }

        Target with(AnnouncementValues merged) { return new Target(row, housingTypeId, created, merged); }
    }

    /**
     * 공고 값을 주택형마다 하나로 모은다. 같은 주택형을 고른 행들이 한 필드에 서로 다른 값을 주면 어느 쪽이 맞는지
     * 알 수 없으므로 그 필드는 지금 값을 유지한다. 저장 전체를 막지 않고 warnings에 남긴다.
     */
    private List<Target> targets(List<Row> planned, List<HousingTypeValue> values, List<String> warnings) {
        var rowsByKey = planned.stream().collect(Collectors.toMap(Row::key, Function.identity()));
        var seen = new HashSet<String>();
        Map<String, Target> result = new LinkedHashMap<>();
        Map<String, Set<String>> conflicts = new HashMap<>();
        for (var value : values) {
            var row = rowsByKey.get(value.rowKey());
            if (row == null) {
                throw new IllegalArgumentException("주택형 공고 값의 공급행이 없거나 중복입니다: " + value.rowKey());
            }
            if (row.excluded() || row.housingTypeId() == null && row.newHousingType() == null) {
                throw new IllegalArgumentException(row.typeName() + ": 제외하지 않고 주택형을 정한 공급행에만 공고 값을 반영합니다.");
            }
            var addressed = addressed(row, value);
            String typeKey = addressed.typeKey();
            if (!seen.add(value.rowKey() + "|" + typeKey)) {
                throw new IllegalArgumentException("주택형 공고 값의 공급행이 없거나 중복입니다: " + value.rowKey());
            }
            // 한쪽만 바꾸면 공고 보증금과 마이홈 월 임대료가 섞인 채 공고문 출처로 남는다.
            if ((value.basicDeposit() == null) != (value.basicMonthlyRent() == null)) {
                throw new IllegalArgumentException(row.typeName() + ": 기본 보증금과 월 임대료는 함께 보내야 합니다.");
            }
            var incoming = new AnnouncementValues(blankToNull(value.name()), value.supplyArea(), value.householdCount(),
                    value.basicDeposit(), value.basicMonthlyRent());
            var previous = result.get(typeKey);
            if (previous == null) {
                result.put(typeKey, addressed.with(incoming));
                continue;
            }
            var kept = conflicts.computeIfAbsent(typeKey, key -> new LinkedHashSet<>());
            var merged = previous.values();
            if (differs(merged.name(), incoming.name())) { kept.add("이름"); }
            if (differs(merged.supplyArea(), incoming.supplyArea())) { kept.add("공급면적"); }
            if (differs(merged.householdCount(), incoming.householdCount())) { kept.add("세대수"); }
            if (differs(merged.basicDeposit(), incoming.basicDeposit())
                    || differs(merged.basicMonthlyRent(), incoming.basicMonthlyRent())) { kept.add("기본 임대조건"); }
            boolean keepPrice = kept.contains("기본 임대조건");
            result.put(typeKey, previous.with(new AnnouncementValues(
                    kept.contains("이름") ? null : firstPresent(merged.name(), incoming.name()),
                    kept.contains("공급면적") ? null : firstPresent(merged.supplyArea(), incoming.supplyArea()),
                    kept.contains("세대수") ? null : firstPresent(merged.householdCount(), incoming.householdCount()),
                    keepPrice ? null : firstPresent(merged.basicDeposit(), incoming.basicDeposit()),
                    keepPrice ? null : firstPresent(merged.basicMonthlyRent(), incoming.basicMonthlyRent()))));
        }
        conflicts.forEach((key, fields) -> {
            if (!fields.isEmpty()) {
                warnings.add(result.get(key).row().typeName() + ": 같은 주택형을 고른 공급행의 공고 값이 서로 달라 "
                        + "지금 값을 유지합니다: " + String.join("·", fields));
            }
        });
        return List.copyOf(result.values());
    }

    /** 공고 값이 가리키는 그 행의 주택형. 연결 주소가 없으면 대표, 있으면 그 행이 함께 모집하는 주택형 중 하나여야 한다. */
    private static Target addressed(Row row, HousingTypeValue value) {
        if (value.linkedHousingTypeId() == null && value.linkedNewHousingTypeArea() == null) {
            return new Target(row, row.housingTypeId(), row.newHousingType(), null);
        }
        if (value.linkedHousingTypeId() != null && value.linkedNewHousingTypeArea() != null) {
            throw new IllegalArgumentException(row.typeName() + ": 주택형 공고 값은 함께 모집하는 기존 주택형이나 새 주택형 중 하나만 가리키세요.");
        }
        return row.linkedHousingTypes().stream()
                .filter(link -> value.linkedHousingTypeId() != null ? value.linkedHousingTypeId().equals(link.housingTypeId())
                        : link.newHousingType() != null
                                && link.newHousingType().exclusiveArea().compareTo(value.linkedNewHousingTypeArea()) == 0)
                .findFirst().map(link -> new Target(row, link.housingTypeId(), link.newHousingType(), null))
                .orElseThrow(() -> new IllegalArgumentException(row.typeName() + ": 주택형 공고 값이 가리키는 주택형을 "
                        + "이 공급행이 함께 모집하지 않습니다."));
    }

    private static <T> T firstPresent(T first, T second) {
        return first != null ? first : second;
    }

    private static boolean differs(Object left, Object right) {
        if (left == null || right == null) { return false; }
        if (left instanceof BigDecimal decimal) { return decimal.compareTo((BigDecimal) right) != 0; }
        return !left.equals(right);
    }

    /** 공고 게시일의 서울 자정. 게시일은 이 저장에서 고친 값을 쓴다. */
    private static Instant postedAt(LocalDate postedDate) {
        return postedDate.atStartOfDay(POSTED_DATE_ZONE).toInstant();
    }

    private static List<String> kept(HousingType type, AnnouncementValues incoming, AnnouncementValues after,
            Instant postedAt) {
        var observed = type.getAnnouncementObservedAt();
        if (observed != null && postedAt.isBefore(observed)) {
            return List.of(type.getName() + ": 더 최근 공고(" + observed.atZone(POSTED_DATE_ZONE).toLocalDate()
                    + ")의 주택형 값을 유지합니다.");
        }
        List<String> result = new ArrayList<>();
        var name = blankToNull(incoming.name());
        if (name != null && !name.strip().equals(after.name())) {
            result.add(type.getName() + ": 관리자가 고친 주택형 이름을 유지합니다.");
        }
        if (incoming.householdCount() != null && !incoming.householdCount().equals(after.householdCount())) {
            result.add(type.getName() + ": 관리자가 고친 세대수를 유지합니다.");
        }
        return result;
    }

    private static Map<String, Object> data(Announcement announcement, LocalDate postedDate, HousingType type) {
        var data = new LinkedHashMap<String, Object>();
        data.put("id", type.getId());
        data.put("name", type.getName());
        data.put("exclusiveArea", type.getExclusiveArea());
        data.put("supplyArea", type.getSupplyArea());
        data.put("householdCount", type.getTotalHouseholdCount());
        var price = type.announcementValues();
        data.put("basicDeposit", price.basicDeposit());
        data.put("basicMonthlyRent", price.basicMonthlyRent());
        data.put("announcementId", announcement.getId());
        data.put("announcementPostedDate", postedDate);
        return data;
    }

    private static TypeValues values(AnnouncementValues values) {
        return new TypeValues(values.name(), values.supplyArea(), values.householdCount(), values.basicDeposit(),
                values.basicMonthlyRent());
    }

    private static boolean same(TypeValues left, TypeValues right) {
        return Objects.equals(left.name(), right.name()) && Objects.equals(left.householdCount(), right.householdCount())
                && Objects.equals(left.basicDeposit(), right.basicDeposit())
                && Objects.equals(left.basicMonthlyRent(), right.basicMonthlyRent())
                && (left.supplyArea() == null ? right.supplyArea() == null
                        : right.supplyArea() != null && left.supplyArea().compareTo(right.supplyArea()) == 0);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
