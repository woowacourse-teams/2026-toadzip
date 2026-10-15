package com.toadzip.backend.interest.service;

import com.toadzip.backend.interest.domain.NotificationTargetType;
import com.toadzip.backend.interest.dto.AdminNotificationFilter;
import com.toadzip.backend.interest.dto.AdminNotificationPage;
import com.toadzip.backend.interest.dto.AdminNotificationPage.Item;
import com.toadzip.backend.interest.dto.AdminNotificationTargets;
import com.toadzip.backend.interest.dto.AdminNotificationTrend;
import com.toadzip.backend.interest.repository.AdminNotificationTargetRepository;
import com.toadzip.backend.interest.repository.NotificationDailyActivityRepository;
import com.toadzip.backend.interest.repository.AdminNotificationQueryRepository;
import com.toadzip.backend.region.repository.RegionCodeResolver;
import com.toadzip.backend.region.repository.RegionSearchRepository;
import com.toadzip.backend.region.repository.RegionSearchResult;
import com.toadzip.backend.user.exception.AdminUserNotFoundException;
import com.toadzip.backend.user.repository.UserRepository;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminNotificationQueryService {

    private final AdminNotificationQueryRepository repository;
    private final AdminNotificationTargetRepository targets;
    private final NotificationDailyActivityRepository dailyActivity;
    private final UserRepository users;
    private final RegionSearchRepository regions;
    private final RegionCodeResolver regionCodes;
    private final Clock clock;

    public AdminNotificationPage search(AdminNotificationFilter filter, int page, int size) {
        if (filter.userId() != null && !users.existsById(filter.userId())) {
            throw new AdminUserNotFoundException();
        }
        AdminNotificationFilter normalized = new AdminNotificationFilter(
                filter.keyword().strip(), filter.userId(), filter.targetType(), filter.targetId(), filter.status(),
                filter.review(), filter.groupTarget());
        Map<String, String> names = regionNames();
        AdminNotificationPage result = repository.search(normalized, page, size,
                clock.instant().truncatedTo(ChronoUnit.MICROS), regionGroups());
        return withRegionNames(result, names);
    }

    public AdminNotificationTargets targets(NotificationTargetType type, int page, int size) {
        Map<String, String> names = regionNames();
        AdminNotificationTargets result = targets.search(type, page, size,
                clock.instant().truncatedTo(ChronoUnit.MICROS), regionGroups());
        List<AdminNotificationTargets.Target> items = result.items().stream().map(target -> {
            if (target.targetType() != NotificationTargetType.REGION) {
                return target;
            }
            return new AdminNotificationTargets.Target(target.targetType(), target.targetId(),
                    names.get(target.targetId()), target.activeMembers());
        }).toList();
        return new AdminNotificationTargets(items, result.page(), result.totalElements(), result.totalPages(),
                result.asOf());
    }

    public AdminNotificationTrend trend(int days) {
        return dailyActivity.trend(days, clock.instant().truncatedTo(ChronoUnit.MICROS));
    }

    private Map<String, String> regionGroups() {
        return regions.findByKeyword("").stream()
                .flatMap(region -> equivalentCodes(region).stream()
                        .map(code -> Map.entry(code, region.regionCode())))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private Map<String, String> regionNames() {
        return regions.findByKeyword("").stream()
                .flatMap(region -> equivalentCodes(region).stream()
                        .map(code -> Map.entry(code, region.displayName())))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private AdminNotificationPage withRegionNames(AdminNotificationPage result, Map<String, String> names) {
        List<Item> items = result.items().stream().map(item -> regionName(item, names)).toList();
        return new AdminNotificationPage(items, result.page(), result.hasNext(), result.totalElements(),
                result.totalPages(), result.summary());
    }

    private Set<String> equivalentCodes(RegionSearchResult region) {
        if (region.regionCode().length() == 2) {
            return regionCodes.equivalentProvinceCodes(region.regionCode())
                    .orElseGet(() -> Set.of(region.regionCode()));
        }
        if (region.regionCode().length() == 10) {
            return regionCodes.filterCodes(region.regionCode())
                    .orElseGet(() -> Set.of(region.regionCode()));
        }
        return regionCodes.equivalentCodes(region.regionCode())
                .orElseGet(() -> Set.of(region.regionCode()));
    }

    private Item regionName(Item item, Map<String, String> names) {
        if (item.targetType() != NotificationTargetType.REGION) {
            return item;
        }
        return new Item(item.id(), item.userId(), item.email(), item.provider(), item.targetType(), item.targetId(),
                names.get(item.targetId()), item.status(), item.requestedAt(), item.expiresAt());
    }
}
