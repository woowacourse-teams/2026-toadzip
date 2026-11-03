package com.toadzip.backend.announcement.service;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.SupplyRow;
import com.toadzip.backend.announcement.dto.correction.SupplyCorrection.LhSupplyRow;
import com.toadzip.backend.announcement.dto.correction.SupplyCorrection.Row;
import com.toadzip.backend.announcement.dto.correction.SupplyCorrection.SaveMode;
import com.toadzip.backend.announcement.dto.correction.SupplyCorrection.Split;
import com.toadzip.backend.ingest.collection.lh.supply.domain.LhAnnouncementSupplySourceView;
import com.toadzip.backend.ingest.enrichment.service.LhSupplyRowLinks;
import com.toadzip.backend.ingest.lhrefinement.service.LhCurrentSupplies;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 공급 보정 요청 행이 LH 공급 원본 행에 놓이는 방식(수정·분리·붙이기·추가)을 미리보기와 적용이 같은 계획으로 검증하고 기록한다.
 * 같은 LH 행을 가리키는 공급행이 분리 기록 없이 둘 이상 생기지 않게 하고, 분리한 행에는 그 LH 행을 계보로 남긴다.
 */
@Component
@RequiredArgsConstructor
class SupplyCorrectionPlacements {
    private final LhCurrentSupplies lhSupplies;

    /** 공고의 최신 LH 공급 원본 행과 각 행에 붙은 공급행·분리행. LH 공고 ID가 없으면 비어 있다. */
    List<LhSupplyRow> lhSupplyRows(Announcement announcement, List<SupplyRow> stored) {
        String panId = announcement.getLhPanId();
        if (panId == null || panId.isBlank()) { return List.of(); }
        return lhSupplies.of(panId).stream().map(supply -> lhSupplyRow(supply, stored)).toList();
    }

    private LhSupplyRow lhSupplyRow(LhAnnouncementSupplySourceView supply, List<SupplyRow> stored) {
        String identifier = LhSupplyRowLinks.identifier(supply);
        String attached = stored.stream().filter(row -> identifier.equals(row.getLhSourceSupplyRowIdentifier()))
                .map(SupplyCorrectionPlacements::key).sorted().findFirst().orElse(null);
        var split = stored.stream().filter(row -> identifier.equals(row.getLhSplitSourceIdentifier()))
                .map(SupplyCorrectionPlacements::key).sorted().toList();
        return new LhSupplyRow(identifier, supply.getComplexLabel(), supply.getTypeName(), supply.getExclusiveArea(),
                count(supply.getSuppliedUnitCount()), attached, split);
    }

    static boolean unattached(LhSupplyRow row) {
        return row.attachedRowKey() == null && row.splitRowKeys().isEmpty();
    }

    /**
     * 요청 행의 저장 방식을 검증하고 분리 묶음과 경고를 만든다. unattached는 작업실이 알린 아직 공급행이 되지 않은 LH 행이다.
     * before·planned는 저장 전·후 행, deleted는 지울 행 키다.
     */
    Plan plan(Announcement announcement, List<SupplyRow> stored, List<Row> requested, List<Row> before, List<Row> planned,
            List<String> deleted, List<LhSupplyRow> unattached) {
        var lhRows = lhSupplyRows(announcement, stored).stream()
                .collect(Collectors.toMap(LhSupplyRow::rowIdentifier, Function.identity(), (first, second) -> first,
                        LinkedHashMap::new));
        var storedById = stored.stream().collect(Collectors.toMap(SupplyRow::getId, Function.identity()));
        Map<String, Placement> placements = new LinkedHashMap<>();
        Set<String> attachedTargets = new HashSet<>();
        for (var row : requested) {
            var placement = placement(row, storedById.get(row.id()), lhRows);
            if (placement == null) { continue; }
            if (placement.mode() == SaveMode.ATTACH && !attachedTargets.add(placement.lhSourceRowIdentifier())) {
                throw new IllegalArgumentException("같은 LH 공급 원본에 새 공급행을 둘 이상 붙일 수 없습니다. "
                        + "주택형별로 나누려면 분리(SPLIT)로 저장하세요.");
            }
            placements.put(row.key(), placement);
        }
        var context = new Context(stored, placements, before, planned, Set.copyOf(deleted), lhRows);
        var splits = new ArrayList<Split>();
        var warnings = new ArrayList<String>();
        for (String target : splitTargets(stored, placements)) {
            splits.add(split(target, context, attachedTargets, warnings));
        }
        var targeted = placements.values().stream().map(Placement::lhSourceRowIdentifier).collect(Collectors.toSet());
        var remaining = unattached.stream().filter(row -> !targeted.contains(row.rowIdentifier())).toList();
        if (!remaining.isEmpty()) {
            warnings.add("LH 공급 원본 " + remaining.size() + "행이 아직 공급행이 되지 않았습니다("
                    + remaining.stream().map(SupplyCorrectionPlacements::label).collect(Collectors.joining(", "))
                    + "). 저장하면 공급정보가 공고문 기준이 되어 정제가 이 행을 공급행으로 만들지 않습니다. "
                    + "같은 공급이면 그 LH 행에 붙이거나(ATTACH) 나눠(SPLIT) 저장하세요.");
        }
        boolean changesLineage = placements.entrySet().stream().anyMatch(entry -> changes(entry, storedById, planned));
        return new Plan(Map.copyOf(placements), List.copyOf(splits), List.copyOf(warnings), remaining, changesLineage);
    }

    private Placement placement(Row row, SupplyRow stored, Map<String, LhSupplyRow> lhRows) {
        var mode = row.resolvedSaveMode();
        String target = row.lhSourceRowIdentifier();
        if (mode == SaveMode.UPDATE || mode == SaveMode.ADD) {
            if ((mode == SaveMode.UPDATE) != (row.id() != null)) {
                throw new IllegalArgumentException(row.typeName() + ": 기존 공급행은 수정(UPDATE), 새 공급행은 추가(ADD)로 저장하세요.");
            }
            if (target != null) {
                throw new IllegalArgumentException(row.typeName() + ": LH 공급 원본 행은 분리(SPLIT)나 붙이기(ATTACH)에서만 지정하세요.");
            }
            return null;
        }
        var lhRow = lhRow(row, target, lhRows);
        if (mode == SaveMode.ATTACH) { return attach(row, lhRow); }
        if (stored != null) { checkSplitAnchor(row, stored, target); }
        return new Placement(SaveMode.SPLIT, target);
    }

    private LhSupplyRow lhRow(Row row, String target, Map<String, LhSupplyRow> lhRows) {
        if (target == null || target.isBlank()) {
            throw new IllegalArgumentException(row.typeName() + ": 분리하거나 붙일 LH 공급 원본 행을 지정하세요.");
        }
        var lhRow = lhRows.get(target);
        if (lhRow == null) {
            throw new IllegalArgumentException(row.typeName() + ": 이 공고의 최신 LH 공급 원본에 없는 행입니다. "
                    + "최신 공급정보를 다시 불러온 뒤 지정하세요.");
        }
        return lhRow;
    }

    private Placement attach(Row row, LhSupplyRow lhRow) {
        if (row.id() != null) {
            throw new IllegalArgumentException(row.typeName() + ": LH 행에 붙이기(ATTACH)는 새 공급행에만 쓸 수 있습니다.");
        }
        if (!unattached(lhRow)) {
            throw new IllegalArgumentException(label(lhRow) + ": 이미 공급행이 된 LH 공급 원본입니다. "
                    + "그 공급행을 수정하거나 주택형별로 나누려면 분리(SPLIT)로 저장하세요.");
        }
        return new Placement(SaveMode.ATTACH, lhRow.rowIdentifier());
    }

    /** 기존 행은 그 LH 행에 붙은 통합행이거나 이미 그 LH 행에서 나눈 행이어야 분리 묶음에 넣을 수 있다. */
    private void checkSplitAnchor(Row row, SupplyRow stored, String target) {
        if (!target.equals(stored.getLhSourceSupplyRowIdentifier()) && !target.equals(stored.getLhSplitSourceIdentifier())) {
            throw new IllegalArgumentException(row.typeName() + ": 이 LH 공급 원본에 붙은 공급행만 그 행에서 나눌 수 있습니다.");
        }
        if (stored.getSupplyGroup() != null) {
            throw new IllegalArgumentException(row.typeName() + ": 공통 모집 묶음 행은 나눌 수 없습니다.");
        }
    }

    private static Set<String> splitTargets(List<SupplyRow> stored, Map<String, Placement> placements) {
        Set<String> targets = new LinkedHashSet<>();
        placements.values().stream().filter(placement -> placement.mode() == SaveMode.SPLIT)
                .map(Placement::lhSourceRowIdentifier).forEach(targets::add);
        stored.stream().map(SupplyRow::getLhSplitSourceIdentifier).filter(Objects::nonNull).sorted().forEach(targets::add);
        return targets;
    }

    private Split split(String target, Context context, Set<String> attachedTargets, List<String> warnings) {
        var lhRow = context.lhRows().get(target);
        var storedSiblings = context.stored().stream().filter(row -> target.equals(row.getLhSplitSourceIdentifier()))
                .map(SupplyCorrectionPlacements::key).collect(Collectors.toSet());
        var members = context.planned().stream().map(Row::key).filter(key -> storedSiblings.contains(key)
                || context.placements().containsKey(key) && target.equals(context.placements().get(key).lhSourceRowIdentifier()))
                .toList();
        boolean requested = context.placements().values().stream()
                .anyMatch(placement -> placement.mode() == SaveMode.SPLIT && target.equals(placement.lhSourceRowIdentifier()));
        String name = target;
        Integer lhCount = null;
        if (lhRow != null) { name = label(lhRow); lhCount = lhRow.householdCount(); }
        Integer total = total(context.planned(), members);
        if (requested) {
            checkRequestedSplit(lhRow, name, members, attachedTargets.contains(target));
            if (total != null && lhCount != null && !total.equals(lhCount)) {
                warnings.add(name + ": 나눈 공급행 모집수 합계 " + total + "가 LH 공급 수 " + lhCount + "와 다릅니다. "
                        + "공고문과 다시 대조하세요.");
            }
        }
        var siblingsBefore = context.before().stream().map(Row::key).filter(storedSiblings::contains).toList();
        boolean removed = siblingsBefore.stream().anyMatch(key -> context.deleted().contains(key)
                || !excluded(context.before(), key) && excluded(context.planned(), key));
        Integer previous = total(context.before(), siblingsBefore);
        if (removed && !Objects.equals(previous, total)) {
            warnings.add(name + ": LH 공급 원본에서 나눈 공급행을 지우거나 제외해 분리 합계가 " + text(previous) + "에서 "
                    + text(total) + "(으)로 바뀝니다.");
        }
        return new Split(target, members, total, lhCount);
    }

    private static void checkRequestedSplit(LhSupplyRow lhRow, String name, List<String> members, boolean attachedHere) {
        if (attachedHere) {
            throw new IllegalArgumentException(name + ": 한 LH 공급 원본을 붙이기(ATTACH)와 분리(SPLIT)에 함께 쓸 수 없습니다.");
        }
        if (lhRow != null && lhRow.attachedRowKey() != null && !members.contains(lhRow.attachedRowKey())) {
            throw new IllegalArgumentException(name + ": 이 LH 행에 붙은 통합행(" + lhRow.attachedRowKey()
                    + ")을 분리 묶음에 넣으세요. 통합행은 첫 분리행이 됩니다.");
        }
        if (members.size() < 2) {
            throw new IllegalArgumentException(name + ": 분리는 같은 LH 공급 원본에서 나온 공급행 둘 이상으로 저장하세요.");
        }
    }

    /** 제외하지 않은 행의 모집수 합계. 미확정 모집수가 있으면 합계도 미확정(null)이다. */
    private static Integer total(List<Row> rows, List<String> keys) {
        var active = rows.stream().filter(row -> keys.contains(row.key()) && !row.excluded()).toList();
        if (active.stream().anyMatch(row -> row.householdCount() == null)) { return null; }
        return active.stream().mapToInt(Row::householdCount).sum();
    }

    private static boolean excluded(List<Row> rows, String key) {
        return rows.stream().anyMatch(row -> row.key().equals(key) && row.excluded());
    }

    private static boolean changes(Map.Entry<String, Placement> entry, Map<Long, SupplyRow> storedById, List<Row> planned) {
        var row = planned.stream().filter(item -> item.key().equals(entry.getKey())).findFirst().orElseThrow();
        if (row.id() == null) { return true; }
        return !entry.getValue().lhSourceRowIdentifier().equals(storedById.get(row.id()).getLhSplitSourceIdentifier());
    }

    /** 적용: 분리행에는 분리 원천을, 붙인 새 행에는 LH 행 식별자를 기록한다. written은 요청 행 키별로 저장한 공급행이다. */
    void apply(Plan plan, Map<String, SupplyRow> written) {
        plan.placements().forEach((key, placement) -> {
            var row = written.get(key);
            if (placement.mode() == SaveMode.ATTACH) {
                row.attachToLhSource(placement.lhSourceRowIdentifier());
                return;
            }
            row.splitFromLhSource(placement.lhSourceRowIdentifier());
        });
    }

    private static String key(SupplyRow row) {
        return "row:" + row.getId();
    }

    private static String label(LhSupplyRow row) {
        return row.complexLabel() + " · " + row.typeName();
    }

    private static String text(Integer count) {
        if (count == null) { return "미확정"; }
        return count.toString();
    }

    private static Integer count(String value) {
        if (value == null || !value.strip().matches("[0-9]{1,9}")) { return null; }
        return Integer.valueOf(value.strip());
    }

    record Placement(SaveMode mode, String lhSourceRowIdentifier) { }

    private record Context(List<SupplyRow> stored, Map<String, Placement> placements, List<Row> before, List<Row> planned,
            Set<String> deleted, Map<String, LhSupplyRow> lhRows) { }

    /** 행 키별 저장 방식(UPDATE·ADD는 없음), 저장 뒤 분리 묶음, 경고, 이 저장 뒤에도 남는 미연결 LH 행. */
    record Plan(Map<String, Placement> placements, List<Split> splits, List<String> warnings,
            List<LhSupplyRow> unattached, boolean changesLineage) { }
}
