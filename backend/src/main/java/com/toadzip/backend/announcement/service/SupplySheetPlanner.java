package com.toadzip.backend.announcement.service;

import com.toadzip.backend.admin.exception.AdminDataConflictException;
import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.AnnouncementComplex;
import com.toadzip.backend.announcement.domain.AnnouncementNameClassification;
import com.toadzip.backend.announcement.domain.AnnouncementSupplyTarget;
import com.toadzip.backend.announcement.domain.SupplyCategory;
import com.toadzip.backend.announcement.dto.correction.SupplySheetInput;
import com.toadzip.backend.announcement.dto.correction.SupplySheetView;
import com.toadzip.backend.announcement.repository.AnnouncementApplicationScheduleRepository;
import com.toadzip.backend.announcement.repository.AnnouncementComplexRepository;
import com.toadzip.backend.announcement.repository.SupplySheetViewQueryRepository;
import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.domain.HousingType;
import com.toadzip.backend.housing.domain.HousingTypeAreas;
import com.toadzip.backend.housing.domain.HousingTypeNames;
import com.toadzip.backend.housing.repository.HousingComplexRepository;
import com.toadzip.backend.housing.repository.HousingTypeRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 공급표 보정 요청({@link SupplySheetInput})을 지금 공급표에 맞춰 저장할 공급표를 정한다. 저장은 하지 않는다.
 *
 * <ul>
 *   <li>요청의 id, 없으면 자연 키로 저장된 개체를 찾는다. 주택형은 (공고 단지 또는 단지명, 신청형 이름), 공급대상은 (표기, 신청 조건),
 *       칸은 (주택형, 공급대상), 수량 묶음은 (종류, 멤버 칸), 임대조건은 멤버 칸으로 맞춘다. 공급표 쓰기와 같은 기준이라 ID가 유지된다.</li>
 *   <li>관리자 수정 표시는 {@link SupplySheetProtection} 규칙으로 정한다. 주택형 값 비교에서 표시 순서는 뺀다.</li>
 *   <li>단지는 있고 주택형이 없는 새·고친 주택형은 같은 이름·면적 주택형을 고르거나 새 주택형을 만든다({@link HousingTypeAreas#pick}).</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
class SupplySheetPlanner {
    private final HousingComplexRepository complexes;
    private final HousingTypeRepository types;
    private final AnnouncementComplexRepository announcementComplexes;
    private final AnnouncementApplicationScheduleRepository schedules;
    private final SupplySheetViewQueryRepository sheetViews;
    private final AnnouncementComplexAdminLinks adminLinks;
    private final ObjectMapper json;

    SupplySheetPlan plan(Announcement announcement, SupplySheetView before, SupplySheetInput input,
            SupplySheetProtection protection) {
        unique(input.housingTypes().stream().map(SupplySheetInput.HousingType::key).toList(), "주택형");
        unique(input.targets().stream().map(SupplySheetInput.Target::key).toList(), "공급대상");
        unique(input.cells().stream().map(SupplySheetInput.Cell::key).toList(), "칸");
        var housingTypes = housingTypes(announcement, before, input.housingTypes(), protection);
        Map<String, Boolean> targetFlags = new HashMap<>();
        var targets = targets(announcement.getId(), before, input, protection, targetFlags);
        var cells = cells(before, input.cells(), housingTypes, targets);
        var after = new SupplySheetView(sorted(housingTypes.values()), targets.values().stream()
                .sorted(Comparator.comparingInt(SupplySheetView.Target::displayOrder)).toList(),
                sortedCells(cells.values(), housingTypes.values()), quotas(before, input.quotas(), cells, protection),
                leases(before, input.leaseTerms(), cells, protection));
        checkNewTypes(after.housingTypes());
        var housingTypeKeys = new LinkedHashMap<String, String>();
        housingTypes.forEach((key, type) -> housingTypeKeys.put(key, type.key()));
        var plan = new SupplySheetPlan(before, after, housingTypeKeys, targetFlags, List.of(), List.of(), List.of(), false);
        // 결과는 버린다. 공급표 쓰기(SupplySheetDraft.of)가 하는 칸·묶음·임대조건 참조 검증을 미리보기에서도 돌려 적용 때 거절될 입력을 먼저 막는다.
        plan.draft(Map.of(), Map.of());
        boolean changed = !canonical(before).equals(canonical(after));
        var warnings = new ArrayList<>(warnings(announcement.getId(), after, changed));
        var links = adminLinks.plan(announcement, before.housingTypes(), after.housingTypes(), touchedIds(before, after));
        checkOneComplexPerAnnouncementComplex(announcement, after.housingTypes(), links);
        warnings.addAll(adminLinks.warnings(links));
        return new SupplySheetPlan(before, after, housingTypeKeys, targetFlags, warnings, links, candidates(after), changed);
    }

    /**
     * 공고 단지 하나는 단지 하나에 이어진다(공고↔단지 연결은 공고 단지로 정한다). 주택형이 고른 단지는 저장 뒤 그 공고 단지의 연결 단지
     * (이번 관리자 연결, 없으면 지금 연결)와 같아야 한다. 아직 단지에 이어지지 않은 공고 단지와 저장 때 만들 공고 단지(단지명)는 처음 고른
     * 단지로 이어지므로 같은 공고 단지의 주택형이 서로 다른 단지를 고르면 막는다. 이번 관리자 연결을 따라갈 주택형(plan.fillKeys)은
     * 보지 않는다.
     */
    private void checkOneComplexPerAnnouncementComplex(Announcement announcement,
            List<SupplySheetView.HousingType> types, List<AnnouncementComplexAdminLinks.Plan> links) {
        Map<String, Long> linked = new HashMap<>();
        announcementComplexes.findAllByAnnouncementOrderByIdAsc(announcement).stream()
                .filter(complex -> complex.linkedComplex() != null)
                .forEach(complex -> linked.put("ac:" + complex.getId(), complex.linkedComplex().getId()));
        links.forEach(link -> linked.put("ac:" + link.target().getId(), link.complex().getId()));
        Set<String> following = links.stream().flatMap(link -> link.fillKeys().stream()).collect(Collectors.toSet());
        for (var type : types) {
            if (type.housingComplexId() == null || following.contains(type.key())) { continue; }
            String place = type.announcementComplexId() != null ? "ac:" + type.announcementComplexId()
                    : "name:" + type.complexName();
            Long complexId = linked.putIfAbsent(place, type.housingComplexId());
            if (complexId != null && !complexId.equals(type.housingComplexId())) {
                throw new IllegalArgumentException(type.complexName() + " · " + type.name()
                        + ": 공고문의 한 단지에 속한 주택형은 같은 단지로 연결하세요.");
            }
        }
    }

    /**
     * 관리자가 손댄 기존 주택형: 주택형 값이 바뀌었거나, 그 주택형 칸의 공급대상·수량 묶음·임대조건이 바뀌었다. 손댄 주택형의 공고 단지를
     * 관리자 연결로 확인한다(옛 공급행의 모집수만 고쳐도 연결을 확인하던 것과 같다).
     */
    private static Set<Long> touchedIds(SupplySheetView before, SupplySheetView after) {
        var beforeById = before.housingTypes().stream()
                .collect(Collectors.toMap(SupplySheetView.HousingType::id, Function.identity()));
        return after.housingTypes().stream().filter(type -> type.id() != null && (!sameValues(beforeById.get(type.id()), type)
                || !cellContent(before, "row:" + type.id()).equals(cellContent(after, type.key()))))
                .map(SupplySheetView.HousingType::id).collect(Collectors.toSet());
    }

    /** 주택형 칸마다 공급대상 표기·신청 조건과 그 칸이 든 수량 묶음·임대조건. 목록 순서와 무관하다. */
    private static List<String> cellContent(SupplySheetView sheet, String housingTypeKey) {
        Map<String, String> targets = new HashMap<>();
        sheet.targets().forEach(target -> targets.put(target.key(), target.label() + "\u0000" + target.applicationCondition()));
        return sheet.cells().stream().filter(cell -> cell.housingType().equals(housingTypeKey)).map(cell -> String.join("\u0001",
                cell.key(), targets.get(cell.target()),
                sheet.quotas().stream().filter(quota -> quota.members().contains(cell.key()))
                        .map(quota -> quota.kind() + ":" + quota.count() + ":" + memberKey(quota.members())).sorted().toList().toString(),
                sheet.leaseTerms().stream().filter(lease -> lease.members().contains(cell.key()))
                        .map(lease -> SupplySheetViewQueryRepository.money(lease.rentalDeposit()) + "/"
                                + SupplySheetViewQueryRepository.money(lease.monthlyRent()) + "/"
                                + SupplySheetViewQueryRepository.money(lease.convertedDeposit()) + ":" + memberKey(lease.members()))
                        .sorted().toList().toString())).sorted().toList();
    }

    /** 표시 순서를 뺀 주택형 값이 같은지. 앞에 끼어든 주택형 때문에 순서만 밀린 주택형은 관리자가 고친 것이 아니다. */
    static boolean sameValues(SupplySheetView.HousingType left, SupplySheetView.HousingType right) {
        return left != null && right != null && Objects.equals(left.announcementComplexId(), right.announcementComplexId())
                && Objects.equals(left.complexName(), right.complexName())
                && Objects.equals(left.housingComplexId(), right.housingComplexId())
                && Objects.equals(left.housingTypeId(), right.housingTypeId())
                && Objects.equals(left.newHousingType(), right.newHousingType()) && Objects.equals(left.name(), right.name())
                && sameNumber(left.exclusiveArea(), right.exclusiveArea()) && sameNumber(left.supplyArea(), right.supplyArea())
                && Objects.equals(left.expectedMoveInMonth(), right.expectedMoveInMonth())
                && Objects.equals(left.supplyPnu(), right.supplyPnu()) && left.category() == right.category();
    }

    private Map<String, SupplySheetView.HousingType> housingTypes(Announcement announcement, SupplySheetView before,
            List<SupplySheetInput.HousingType> input, SupplySheetProtection protection) {
        var beforeById = before.housingTypes().stream()
                .collect(Collectors.toMap(SupplySheetView.HousingType::id, Function.identity(), (left, right) -> left, LinkedHashMap::new));
        Set<Long> claimed = new HashSet<>();
        Map<String, Long> ids = new HashMap<>();
        for (var type : input) {
            if (type.id() == null) { continue; }
            if (!beforeById.containsKey(type.id()) || !claimed.add(type.id())) {
                throw new IllegalArgumentException(type.name() + ": 이 공고에 없거나 두 번 보낸 주택형입니다. 최신 공급정보를 다시 불러오세요.");
            }
            ids.put(type.key(), type.id());
        }
        for (var type : input) {
            if (type.id() != null) { continue; }
            beforeById.values().stream().filter(stored -> !claimed.contains(stored.id()) && sameHousingType(stored, type))
                    .findFirst().ifPresent(stored -> {
                        claimed.add(stored.id());
                        ids.put(type.key(), stored.id());
                    });
        }
        Set<String> naturalKeys = new HashSet<>();
        for (var type : input) {
            String place = type.announcementComplexId() != null ? "ac:" + type.announcementComplexId() : "name:" + type.complexName().strip();
            if (!naturalKeys.add(place + "\u0000" + type.name().strip())) {
                throw new IllegalArgumentException(type.complexName().strip() + " · " + type.name().strip()
                        + ": 한 단지에서 주택형(신청형)은 하나입니다. 한 줄로 합치세요.");
            }
        }
        var ownComplexes = announcementComplexes.findAllByAnnouncementOrderByIdAsc(announcement).stream()
                .collect(Collectors.toMap(AnnouncementComplex::getId, Function.identity()));
        // 공급 구분을 비워 보내면(PDF 보정의 새 주택형 등) LH 정제와 같은 공고명 규칙으로 정한다.
        var defaultCategory = AnnouncementNameClassification.supplyCategory(announcement.getName());
        Map<String, SupplySheetView.HousingType> result = new LinkedHashMap<>();
        for (var type : input) {
            Long id = ids.get(type.key());
            var stored = id == null ? null : beforeById.get(id);
            result.put(type.key(), housingType(type, id, stored, ownComplexes, protection, defaultCategory));
        }
        return result;
    }

    private SupplySheetView.HousingType housingType(SupplySheetInput.HousingType type, Long id,
            SupplySheetView.HousingType stored, Map<Long, AnnouncementComplex> ownComplexes,
            SupplySheetProtection protection, SupplyCategory defaultCategory) {
        String name = type.name().strip();
        var category = type.category() != null ? type.category() : defaultCategory;
        Long announcementComplexId = type.announcementComplexId();
        if (announcementComplexId != null && !ownComplexes.containsKey(announcementComplexId)) {
            throw new IllegalArgumentException(name + ": 이 공고의 공고 단지가 아닙니다.");
        }
        // 휴지통 단지는 새로 연결하지 못한다. 이미 연결된 주택형은 그대로 보내면 막지 않는다(다른 값을 저장할 수 있게).
        boolean keepsComplex = stored != null && type.housingComplexId() != null
                && type.housingComplexId().equals(stored.housingComplexId());
        var complex = keepsComplex ? storedComplex(type.housingComplexId()) : complex(type.housingComplexId());
        Long typeId = type.housingTypeId();
        var created = type.newHousingType();
        if (created != null && (complex == null || typeId != null)) {
            throw new IllegalArgumentException("새 주택형을 만들 단지만 선택하세요. 기존 주택형과 함께 지정할 수 없습니다.");
        }
        if (typeId != null) { existingType(typeId, complex); }
        if (announcementComplexId == null && stored == null && complex != null) {
            // 새 주택형은 공고에서 그 단지에 연결된 공고 단지가 하나일 때 그 공고 단지를 가리킨다.
            var linked = ownComplexes.values().stream().filter(candidate -> candidate.linkedComplex() != null
                    && candidate.linkedComplex().getId().equals(complex.getId())).toList();
            if (linked.size() == 1) { announcementComplexId = linked.getFirst().getId(); }
        }
        if (announcementComplexId == null) {
            // 공고 단지를 고르지 않은 주택형은 같은 이름의 공고 단지에 든다. 없으면 저장 때 만든다(AnnouncementComplexAdminLinks.ensure).
            announcementComplexId = ownComplexes.values().stream()
                    .filter(candidate -> candidate.getSourceComplexName().strip().equals(type.complexName().strip()))
                    .map(AnnouncementComplex::getId).findFirst().orElse(null);
        }
        BigDecimal exclusiveArea = SupplySheetViewQueryRepository.area(type.exclusiveArea());
        BigDecimal supplyArea = SupplySheetViewQueryRepository.area(type.supplyArea());
        String key = id != null ? "row:" + id : type.key();
        var requested = view(key, id, announcementComplexId, type, category, complex, typeId, created, exclusiveArea, supplyArea, false);
        // 단지만 정하고 주택형을 비운 새·고친 주택형은 면적으로 주택형을 정한다. 주택형이 있던 것을 비우면 관리자가 연결을 끊은 것이다.
        if (complex != null && typeId == null && created == null && exclusiveArea != null && exclusiveArea.signum() > 0
                && (stored == null || stored.housingTypeId() == null && !sameValues(stored, requested))) {
            var picked = HousingTypeAreas.pick(types.findAllByHousingComplex(complex), exclusiveArea, name);
            if (picked.isPresent()) {
                typeId = picked.get().getId();
            } else {
                created = new SupplySheetInput.NewHousingType(newTypeName(name), exclusiveArea, supplyArea);
            }
            requested = view(key, id, announcementComplexId, type, category, complex, typeId, created, exclusiveArea, supplyArea, false);
        }
        boolean modified = protection.adminModified(stored != null, stored != null && stored.adminModified(),
                sameValues(stored, requested));
        return view(key, id, announcementComplexId, type, category, complex, typeId, created, exclusiveArea, supplyArea, modified);
    }

    private static SupplySheetView.HousingType view(String key, Long id, Long announcementComplexId,
            SupplySheetInput.HousingType type, SupplyCategory category, HousingComplex complex, Long typeId,
            SupplySheetInput.NewHousingType created,
            BigDecimal exclusiveArea, BigDecimal supplyArea, boolean modified) {
        return new SupplySheetView.HousingType(key, id, announcementComplexId, type.complexName().strip(),
                complex == null ? null : complex.getId(), complex == null ? null : complex.getName(), typeId, created,
                type.name().strip(), exclusiveArea, supplyArea, type.expectedMoveInMonth(), blankToNull(type.supplyPnu()),
                category, type.displayOrder(), modified);
    }

    /** 공급표 쓰기의 자연 키와 같다: 신청형 이름과, 공고 단지가 한쪽이라도 있으면 공고 단지, 없으면 단지명. */
    private static boolean sameHousingType(SupplySheetView.HousingType stored, SupplySheetInput.HousingType type) {
        if (!stored.name().strip().equals(type.name().strip())) { return false; }
        if (stored.announcementComplexId() != null || type.announcementComplexId() != null) {
            return Objects.equals(stored.announcementComplexId(), type.announcementComplexId());
        }
        return stored.complexName().strip().equals(type.complexName().strip());
    }

    /**
     * 새 단지 주택형 이름. 단지 주택형은 다른 공고와 단지 상세가 함께 쓰므로 이 공고의 표기를 이어 적은 이름(26A·26B,
     * "36A형(고령자) · 36A형(청년)")을 그대로 쓰지 않고 첫 표기에서 끝 괄호(신청자 구분)를 뗀 이름으로 만든다.
     */
    private static String newTypeName(String typeName) {
        String first = typeName.split("[·]")[0].strip();
        String core = HousingTypeNames.withoutApplicant(first);
        return core.isEmpty() ? first : core;
    }

    /** 칸이 있는 공급대상만 둔다. 저장된 공급대상은 (표기, 신청 조건)으로 찾는다. */
    private Map<String, SupplySheetView.Target> targets(long announcementId, SupplySheetView before,
            SupplySheetInput input, SupplySheetProtection protection, Map<String, Boolean> flags) {
        var stored = before.targets().stream().collect(Collectors.toMap(
                target -> AnnouncementSupplyTarget.key(target.label(), target.applicationCondition()), Function.identity()));
        Set<Long> storedIds = before.targets().stream().map(SupplySheetView.Target::id).collect(Collectors.toSet());
        var storedFlags = sheetViews.targetAdminModified(announcementId);
        Set<String> used = input.cells().stream().map(SupplySheetInput.Cell::target).collect(Collectors.toSet());
        Set<String> seen = new HashSet<>();
        Map<String, SupplySheetView.Target> result = new LinkedHashMap<>();
        for (var target : input.targets()) {
            if (target.id() != null && !storedIds.contains(target.id())) {
                throw new IllegalArgumentException(target.label().strip() + ": 이 공고에 없는 공급대상입니다. 최신 공급정보를 다시 불러오세요.");
            }
            if (!used.contains(target.key())) { continue; }
            String label = target.label().strip();
            String condition = target.applicationCondition() == null ? "" : target.applicationCondition().strip();
            String naturalKey = AnnouncementSupplyTarget.key(label, condition);
            if (!seen.add(naturalKey)) {
                throw new IllegalArgumentException(label + ": 같은 공급대상(표기, 신청 조건)이 두 번 있습니다.");
            }
            var match = stored.get(naturalKey);
            String key = match != null ? match.key() : target.key();
            flags.put(key, protection.adminModified(match != null,
                    match != null && storedFlags.getOrDefault(match.id(), false),
                    match != null && match.displayOrder() == target.displayOrder()));
            result.put(target.key(), new SupplySheetView.Target(key, match == null ? null : match.id(), label, condition,
                    target.displayOrder()));
        }
        return result;
    }

    private static Map<String, SupplySheetView.Cell> cells(SupplySheetView before, List<SupplySheetInput.Cell> input,
            Map<String, SupplySheetView.HousingType> housingTypes, Map<String, SupplySheetView.Target> targets) {
        Map<String, SupplySheetView.Cell> stored = new HashMap<>();
        before.cells().forEach(cell -> stored.put(cell.housingType() + "\u0000" + cell.target(), cell));
        Set<String> places = new HashSet<>();
        Map<String, SupplySheetView.Cell> result = new LinkedHashMap<>();
        for (var cell : input) {
            var type = housingTypes.get(cell.housingType());
            var target = targets.get(cell.target());
            if (type == null || target == null) {
                throw new IllegalArgumentException("칸이 가리키는 주택형이나 공급대상이 공급표에 없습니다: " + cell.key());
            }
            String place = type.key() + "\u0000" + target.key();
            if (!places.add(place)) {
                throw new IllegalArgumentException(type.name() + ": 한 주택형에 같은 공급대상 칸이 두 번 있습니다.");
            }
            var match = type.id() != null && target.id() != null ? stored.get(place) : null;
            result.put(cell.key(), new SupplySheetView.Cell(match != null ? match.key() : cell.key(),
                    match == null ? null : match.id(), type.key(), target.key(), cell.displayOrder()));
        }
        return result;
    }

    private static List<SupplySheetView.Quota> quotas(SupplySheetView before, List<SupplySheetInput.Quota> input,
            Map<String, SupplySheetView.Cell> cells, SupplySheetProtection protection) {
        Map<String, SupplySheetView.Quota> stored = new HashMap<>();
        before.quotas().forEach(quota -> stored.putIfAbsent(quota.kind() + "|" + memberKey(quota.members()), quota));
        Set<String> seen = new HashSet<>();
        List<SupplySheetView.Quota> result = new ArrayList<>();
        for (var quota : input) {
            var members = members(quota.members(), cells, "모집·예비 수");
            String place = quota.kind() + "|" + memberKey(members);
            if (!seen.add(place)) {
                throw new IllegalArgumentException("같은 칸들에 같은 종류의 모집·예비 수가 두 번 있습니다.");
            }
            var match = allStored(members, cells) ? stored.get(place) : null;
            boolean modified = protection.adminModified(match != null, match != null && match.adminModified(),
                    match != null && match.count() == quota.count());
            result.add(new SupplySheetView.Quota(match != null ? match.key() : "new:quota:" + (result.size() + 1),
                    match == null ? null : match.id(), quota.kind(), quota.count(), members, modified));
        }
        return result;
    }

    private static List<SupplySheetView.Lease> leases(SupplySheetView before, List<SupplySheetInput.Lease> input,
            Map<String, SupplySheetView.Cell> cells, SupplySheetProtection protection) {
        Map<String, SupplySheetView.Lease> stored = new HashMap<>();
        before.leaseTerms().forEach(lease -> stored.putIfAbsent(memberKey(lease.members()), lease));
        Set<String> leased = new HashSet<>();
        List<SupplySheetView.Lease> result = new ArrayList<>();
        for (var lease : input) {
            var members = members(lease.members(), cells, "임대조건");
            if (lease.rentalDeposit() == null && lease.monthlyRent() == null && lease.convertedDeposit() == null) {
                throw new IllegalArgumentException("임대조건에는 금액이 하나 이상 있어야 합니다.");
            }
            if (members.stream().anyMatch(member -> !leased.add(member))) {
                throw new IllegalArgumentException("칸 하나에는 임대조건 하나만 둘 수 있습니다.");
            }
            var deposit = SupplySheetViewQueryRepository.money(lease.rentalDeposit());
            var rent = SupplySheetViewQueryRepository.money(lease.monthlyRent());
            var converted = SupplySheetViewQueryRepository.money(lease.convertedDeposit());
            var match = allStored(members, cells) ? stored.get(memberKey(members)) : null;
            boolean same = match != null && sameNumber(match.rentalDeposit(), deposit) && sameNumber(match.monthlyRent(), rent)
                    && sameNumber(match.convertedDeposit(), converted);
            boolean modified = protection.adminModified(match != null, match != null && match.adminModified(), same);
            result.add(new SupplySheetView.Lease(match != null ? match.key() : "new:lease:" + (result.size() + 1),
                    match == null ? null : match.id(), deposit, rent, converted, members, modified));
        }
        return result;
    }

    /** 요청 칸 key를 저장할 칸 key로 바꾼다. */
    private static List<String> members(List<String> keys, Map<String, SupplySheetView.Cell> cells, String name) {
        Set<String> seen = new HashSet<>();
        List<String> result = new ArrayList<>();
        for (var key : keys) {
            var cell = cells.get(key);
            if (cell == null || !seen.add(key)) {
                throw new IllegalArgumentException(name + "의 칸이 공급표에 없거나 겹칩니다: " + key);
            }
            result.add(cell.key());
        }
        return result;
    }

    private static boolean allStored(List<String> members, Map<String, SupplySheetView.Cell> cells) {
        var storedKeys = cells.values().stream().filter(cell -> cell.id() != null).map(SupplySheetView.Cell::key)
                .collect(Collectors.toSet());
        return storedKeys.containsAll(members);
    }

    private static String memberKey(List<String> members) {
        return members.stream().sorted().collect(Collectors.joining(","));
    }

    private static List<SupplySheetView.HousingType> sorted(java.util.Collection<SupplySheetView.HousingType> types) {
        return types.stream().sorted(Comparator.comparingInt(SupplySheetView.HousingType::displayOrder)).toList();
    }

    private static List<SupplySheetView.Cell> sortedCells(java.util.Collection<SupplySheetView.Cell> cells,
            java.util.Collection<SupplySheetView.HousingType> types) {
        Map<String, Integer> order = new HashMap<>();
        sorted(types).forEach(type -> order.put(type.key(), order.size()));
        return cells.stream().sorted(Comparator.comparing((SupplySheetView.Cell cell) -> order.get(cell.housingType()))
                .thenComparingInt(SupplySheetView.Cell::displayOrder)).toList();
    }

    /**
     * 새 주택형은 단지에 이름이 같은 주택형({@link HousingTypeNames#sameType})이 없을 때만 만든다. 있으면 그 주택형을 고르게 하고,
     * 휴지통에 있으면 복구한 뒤 연결하게 한다. 한 저장에서 단지·이름이 같은 새 주택형은 하나만 만들므로 면적이 달라도 거절한다.
     */
    private void checkNewTypes(List<SupplySheetView.HousingType> planned) {
        Map<String, BigDecimal> areasByName = new HashMap<>();
        for (var type : planned) {
            var input = type.newHousingType();
            if (input == null) { continue; }
            var area = areasByName.putIfAbsent(newTypeKey(type), HousingTypeAreas.key(input.exclusiveArea()));
            if (area != null && area.compareTo(HousingTypeAreas.key(input.exclusiveArea())) != 0) {
                throw new IllegalArgumentException(type.name() + ": '" + input.name() + "' 새 주택형을 전용면적을 다르게 두 번 만들 수 없습니다. "
                        + "이름이나 면적을 맞추세요.");
            }
            var same = types.findAllIncludingTrashByHousingComplexId(type.housingComplexId()).stream()
                    .filter(stored -> HousingTypeNames.sameType(stored.getName(), stored.getExclusiveArea(), input.name(),
                            input.exclusiveArea())).toList();
            String label = type.name() + ": '" + input.name() + "' 주택형이 ";
            if (same.stream().anyMatch(stored -> !stored.isAdminDeleted())) {
                throw new IllegalArgumentException(label + "이 단지에 있습니다. 새로 만들지 말고 그 주택형을 선택하세요.");
            }
            if (!same.isEmpty()) {
                throw new IllegalArgumentException(label + "휴지통에 있습니다. 단지 상세에서 복구한 뒤 연결하세요.");
            }
        }
    }

    /** 한 저장에서 같은 단지·이름의 새 주택형은 주택형이 달라도 하나를 만들어 함께 쓴다. */
    static String newTypeKey(SupplySheetView.HousingType type) {
        return type.housingComplexId() + ":" + HousingTypeNames.key(type.newHousingType().name());
    }

    /** 바꾸는 저장이 접수 일정이 걸린 단지의 연결을 없애면 거절한다. 지금 공급표 그대로인 저장은 막지 않는다. */
    private List<String> warnings(long announcementId, SupplySheetView after, boolean changed) {
        var linked = after.housingTypes().stream().map(SupplySheetView.HousingType::housingComplexId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        if (changed && schedules.findAllByAnnouncementIdIn(List.of(announcementId)).stream().anyMatch(schedule ->
                schedule.getHousingComplex() != null && !linked.contains(schedule.getHousingComplex().getId()))) {
            throw new AdminDataConflictException("연결을 바꾸거나 지울 단지에 접수 일정이 있습니다. 일정 적용 단지를 먼저 수정하세요.");
        }
        List<String> warnings = new ArrayList<>();
        Set<String> withCells = after.cells().stream().map(SupplySheetView.Cell::housingType).collect(Collectors.toSet());
        for (var type : after.housingTypes()) {
            if (type.housingComplexId() == null || type.housingTypeId() == null && type.newHousingType() == null) {
                warnings.add(type.complexName() + " · " + type.name() + ": 단지 또는 주택형 연결이 미확정입니다.");
            }
            if (!withCells.contains(type.key())) {
                warnings.add(type.name() + ": 표시할 공급대상이 없습니다.");
            }
        }
        if (after.housingTypes().isEmpty()) { warnings.add("표시할 주택형이 없어 단지 검색에 공고가 나타나지 않습니다."); }
        return warnings;
    }

    /** 확인 토큰에 넣는 연결 후보 값. 미리보기 뒤 단지·주택형이 바뀌면 적용을 거절한다. */
    private List<Object> candidates(SupplySheetView after) {
        Map<Long, Object> result = new TreeMap<>();
        Map<Long, Object> typeValues = new TreeMap<>();
        for (var type : after.housingTypes()) {
            var complex = type.housingComplexId() == null ? null : storedComplex(type.housingComplexId());
            if (complex != null) { result.put(complex.getId(), List.of(complex.getId(), complex.getVersion(), complex.getName())); }
            if (type.housingTypeId() != null) {
                var stored = types.findById(type.housingTypeId()).orElseThrow();
                var value = new LinkedHashMap<String, Object>();
                value.put("id", stored.getId()); value.put("name", stored.getName());
                value.put("exclusiveArea", stored.getExclusiveArea()); value.put("supplyArea", stored.getSupplyArea());
                value.put("householdCount", stored.getTotalHouseholdCount());
                typeValues.put(stored.getId(), value);
            }
        }
        return List.of(List.copyOf(result.values()), List.copyOf(typeValues.values()));
    }

    /** 목록 순서와 무관하게 같은 공급표인지 가리는 값. */
    private Object canonical(SupplySheetView sheet) {
        var value = new LinkedHashMap<String, Object>();
        value.put("housingTypes", sheet.housingTypes().stream().sorted(Comparator.comparing(SupplySheetView.HousingType::key))
                .map(type -> new SupplySheetView.HousingType(type.key(), type.id(), type.announcementComplexId(), type.complexName(),
                        type.housingComplexId(), null, type.housingTypeId(), type.newHousingType(), type.name(),
                        SupplySheetViewQueryRepository.area(type.exclusiveArea()), SupplySheetViewQueryRepository.area(type.supplyArea()),
                        type.expectedMoveInMonth(), type.supplyPnu(), type.category(), type.displayOrder(), type.adminModified()))
                .toList());
        value.put("targets", sheet.targets().stream().sorted(Comparator.comparing(SupplySheetView.Target::key)).toList());
        value.put("cells", sheet.cells().stream().sorted(Comparator.comparing(SupplySheetView.Cell::key)).toList());
        value.put("quotas", sheet.quotas().stream().map(quota -> List.of(quota.kind(), quota.count(), memberKey(quota.members()),
                quota.adminModified())).sorted(Comparator.comparing(Object::toString)).toList());
        value.put("leases", sheet.leaseTerms().stream().map(lease -> java.util.Arrays.asList(String.valueOf(lease.rentalDeposit()),
                String.valueOf(lease.monthlyRent()), String.valueOf(lease.convertedDeposit()), memberKey(lease.members()),
                lease.adminModified())).sorted(Comparator.comparing(Object::toString)).toList());
        return json.valueToTree(value);
    }

    private HousingComplex complex(Long id) {
        if (id == null) { return null; }
        return complexes.findById(id).filter(value -> !value.isAdminDeleted())
                .orElseThrow(() -> new AdminDataConflictException("연결할 단지가 없거나 휴지통에 있습니다."));
    }

    private HousingComplex storedComplex(long id) {
        return complexes.findById(id).orElseThrow(() -> new AdminDataConflictException("연결할 단지가 없거나 휴지통에 있습니다."));
    }

    private HousingType existingType(long id, HousingComplex complex) {
        var type = types.findById(id).orElseThrow(() -> new AdminDataConflictException("연결할 주택형을 찾을 수 없습니다."));
        if (complex == null || !type.getHousingComplex().getId().equals(complex.getId())) {
            throw new IllegalArgumentException("선택한 단지에 속한 주택형을 선택하세요.");
        }
        return type;
    }

    private static void unique(List<String> keys, String name) {
        Set<String> seen = new HashSet<>();
        for (var key : keys) {
            if (!seen.add(key)) { throw new IllegalArgumentException(name + " 작업 키가 중복되었습니다: " + key); }
        }
    }

    private static boolean sameNumber(BigDecimal left, BigDecimal right) {
        return left == null ? right == null : right != null && left.compareTo(right) == 0;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
