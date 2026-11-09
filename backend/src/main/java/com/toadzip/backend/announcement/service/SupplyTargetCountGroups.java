package com.toadzip.backend.announcement.service;

import com.toadzip.backend.announcement.domain.SupplyRow;
import com.toadzip.backend.announcement.domain.SupplyTarget;
import com.toadzip.backend.announcement.domain.SupplyTargetCountGroup;
import com.toadzip.backend.announcement.domain.SupplyTargetCountGroup.Kind;
import com.toadzip.backend.announcement.dto.correction.SupplyCorrection.Target;
import com.toadzip.backend.announcement.dto.correction.SupplyCorrection.TargetCountGroup;
import com.toadzip.backend.announcement.repository.SupplyTargetCountGroupRepository;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 공급대상끼리 함께 쓰는 모집수(공통 모집수)의 검증·읽기·쓰기. 한 공급행 안에서 종류(공급세대수·예비자수)마다 묶고,
 * 묶음 수는 한 번만 저장한다. 공급행끼리의 공통 모집 묶음(SupplyRowGroup)과는 다르다.
 */
@Component
@RequiredArgsConstructor
class SupplyTargetCountGroups {

    private final SupplyTargetCountGroupRepository groups;

    /** 같은 key의 묶음은 제외하지 않은 대상 둘 이상이 같은 수로 함께 쓰고, 묶인 대상은 그 종류의 칸이 비어 있어야 한다. */
    void validate(String rowLabel, List<Target> targets) {
        for (Kind kind : Kind.values()) {
            Map<String, List<Target>> members = new LinkedHashMap<>();
            for (var target : targets) {
                var group = group(target, kind);
                if (group == null) { continue; }
                if (target.excluded()) {
                    throw new IllegalArgumentException(rowLabel + " · " + target.target() + ": 제외한 공급대상은 공통 "
                            + label(kind) + "에 넣을 수 없습니다.");
                }
                if (ownCount(target, kind) != null) {
                    throw new IllegalArgumentException(rowLabel + " · " + target.target() + ": 공통 " + label(kind)
                            + "를 쓰는 대상에는 그 대상만의 " + label(kind) + "를 넣을 수 없습니다.");
                }
                members.computeIfAbsent(group.key(), key -> new ArrayList<>()).add(target);
            }
            for (var shared : members.values()) { checkMembers(rowLabel, kind, shared); }
        }
    }

    private void checkMembers(String rowLabel, Kind kind, List<Target> shared) {
        if (shared.size() < 2) {
            throw new IllegalArgumentException(rowLabel + ": 공통 " + label(kind) + "는 공급대상 둘 이상이 함께 써야 합니다.");
        }
        if (shared.stream().map(target -> group(target, kind).count()).distinct().count() > 1) {
            throw new IllegalArgumentException(rowLabel + ": 같은 공통 " + label(kind) + "를 쓰는 대상끼리 수가 다릅니다.");
        }
    }

    /**
     * 요청 묶음이 저장된 묶음과 같은 대상·같은 수이면 저장된 key("group:{id}")로 바꾼다. PDF 보정 화면은 묶음 key를 새로 정해
     * 보내므로(pdf:0) 그대로 두면 같은 묶음도 바뀐 공급정보로 보여 '변경 없음'에 닿지 못한다.
     */
    List<Target> withStoredKeys(List<Target> stored, List<Target> requested) {
        var result = requested;
        for (Kind kind : Kind.values()) {
            Map<List<Object>, String> storedKeys = new HashMap<>();
            members(stored, kind).forEach((key, signature) -> storedKeys.put(signature, key));
            Map<String, String> renamed = new HashMap<>();
            members(result, kind).forEach((key, signature) -> {
                if (storedKeys.containsKey(signature)) { renamed.put(key, storedKeys.get(signature)); }
            });
            result = result.stream().map(target -> renamed(target, kind, renamed)).toList();
        }
        return result;
    }

    /** 묶음 key → (수, 대상 작업 키 모음). */
    private static Map<String, List<Object>> members(List<Target> targets, Kind kind) {
        Map<String, List<Object>> result = new LinkedHashMap<>();
        for (var target : targets) {
            var group = group(target, kind);
            if (group == null) { continue; }
            var entry = result.computeIfAbsent(group.key(), key -> List.of(group.count(), new TreeSet<String>()));
            ((TreeSet<String>) entry.get(1)).add(target.key());
        }
        return result;
    }

    private static Target renamed(Target target, Kind kind, Map<String, String> renamed) {
        var group = group(target, kind);
        if (group == null || !renamed.containsKey(group.key())) { return target; }
        var stored = new TargetCountGroup(renamed.get(group.key()), group.count());
        if (kind == Kind.SUPPLY) { return target.withCountGroups(stored, target.reserveCountGroup()); }
        return target.withCountGroups(target.householdCountGroup(), stored);
    }

    /** 저장된 묶음을 작업 화면 값으로 읽는다. */
    TargetCountGroup read(SupplyTargetCountGroup group) {
        if (group == null) { return null; }
        return new TargetCountGroup("group:" + group.getId(), group.getHouseholdCount());
    }

    /**
     * 대상 값을 고치기 전에, 묶음이 바뀌는 종류는 지금 대상을 옛 묶음에서 뺀다. 그래야 묶음에서 빠지며 자기 수를 갖는 대상이
     * 옛 묶음 검사에 걸리지 않는다. 바뀐 종류와 지울 옛 묶음을 돌려준다.
     */
    Release release(List<SupplyTarget> stored, List<Target> requested) {
        Set<Kind> changed = new HashSet<>();
        List<SupplyTargetCountGroup> old = new ArrayList<>();
        for (Kind kind : Kind.values()) {
            if (partition(requested, kind).equals(storedPartition(stored, kind))) { continue; }
            changed.add(kind);
            for (var target : stored) {
                var group = stored(target, kind);
                if (group != null && !old.contains(group)) { old.add(group); }
            }
        }
        for (var target : stored) {
            target.shareCounts(keep(target, Kind.SUPPLY, changed), keep(target, Kind.RESERVE, changed));
        }
        return new Release(changed, old);
    }

    /** 대상을 다 쓴 뒤 바뀐 종류의 묶음을 새로 만들어 대상에 넣고, 옛 묶음을 지운다. */
    void attach(Release release, SupplyRow row, Map<String, SupplyTarget> written, List<Target> requested) {
        Map<Kind, Map<String, SupplyTargetCountGroup>> created = new EnumMap<>(Kind.class);
        for (var input : requested) {
            var target = written.get(input.key());
            target.shareCounts(next(release, row, created, input, Kind.SUPPLY, target),
                    next(release, row, created, input, Kind.RESERVE, target));
        }
        groups.deleteAll(release.old());
    }

    private SupplyTargetCountGroup next(Release release, SupplyRow row, Map<Kind, Map<String, SupplyTargetCountGroup>> created,
            Target input, Kind kind, SupplyTarget target) {
        if (!release.changed().contains(kind)) { return stored(target, kind); }
        var group = group(input, kind);
        if (group == null) { return null; }
        return created.computeIfAbsent(kind, value -> new HashMap<>()).computeIfAbsent(group.key(),
                key -> groups.save(SupplyTargetCountGroup.create(row, kind, group.count())));
    }

    private SupplyTargetCountGroup keep(SupplyTarget target, Kind kind, Set<Kind> changed) {
        if (changed.contains(kind)) { return null; }
        return stored(target, kind);
    }

    /** 묶음을 (수, 대상 작업 키 묶음)의 모음으로 비교한다. key 표기는 보지 않는다. */
    private static Set<List<Object>> partition(List<Target> targets, Kind kind) {
        return grouped(targets, target -> groupKey(target, kind), target -> group(target, kind).count(), Target::key);
    }

    private static Set<List<Object>> storedPartition(List<SupplyTarget> targets, Kind kind) {
        return grouped(targets, target -> storedKey(target, kind), target -> stored(target, kind).getHouseholdCount(),
                target -> "target:" + target.getId());
    }

    private static <T> Set<List<Object>> grouped(List<T> targets, Function<T, String> key, Function<T, Integer> count,
            Function<T, String> member) {
        Map<String, List<Object>> result = new LinkedHashMap<>();
        for (var target : targets) {
            String groupKey = key.apply(target);
            if (groupKey == null) { continue; }
            var entry = result.computeIfAbsent(groupKey, value -> List.of(count.apply(target), new TreeSet<String>()));
            ((TreeSet<String>) entry.get(1)).add(member.apply(target));
        }
        return new HashSet<>(result.values());
    }

    private static String groupKey(Target target, Kind kind) {
        var group = group(target, kind);
        if (group == null) { return null; }
        return group.key();
    }

    private static String storedKey(SupplyTarget target, Kind kind) {
        var group = stored(target, kind);
        if (group == null) { return null; }
        return String.valueOf(group.getId());
    }

    private static TargetCountGroup group(Target target, Kind kind) {
        if (kind == Kind.SUPPLY) { return target.householdCountGroup(); }
        return target.reserveCountGroup();
    }

    private static Integer ownCount(Target target, Kind kind) {
        if (kind == Kind.SUPPLY) { return target.householdCount(); }
        return target.reserveCount();
    }

    private static SupplyTargetCountGroup stored(SupplyTarget target, Kind kind) {
        if (kind == Kind.SUPPLY) { return target.getSupplyCountGroup(); }
        return target.getReserveCountGroup();
    }

    private static String label(Kind kind) {
        if (kind == Kind.SUPPLY) { return "모집수"; }
        return "예비자 수";
    }

    record Release(Set<Kind> changed, List<SupplyTargetCountGroup> old) {
        Release {
            changed = Set.copyOf(changed);
            old = List.copyOf(old);
            Objects.requireNonNull(changed);
        }
    }
}
