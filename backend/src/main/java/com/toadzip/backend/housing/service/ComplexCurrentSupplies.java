package com.toadzip.backend.housing.service;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.SupplySheet;
import com.toadzip.backend.announcement.repository.AnnouncementRepository;
import com.toadzip.backend.announcement.service.AnnouncementSupplies;
import com.toadzip.backend.housing.repository.CurrentAnnouncementRow;
import com.toadzip.backend.housing.repository.CurrentAnnouncementTargetRow;
import com.toadzip.backend.housing.repository.CurrentSupplyConditionRow;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 단지 상세의 현재 공고 공급(주택타입별 공급조건, 공고별 공급대상). 공고 공급은 공급 읽기 입구({@link AnnouncementSupplies})로 읽는다:
 * 확정한 공고는 정본, 확정 전 공고는 준비 데이터다. 공급조건은 이 단지 주택타입에 이어진 공급행의 금액 있는 칸이고, 공급대상은 이 단지
 * 공급행의 '전체'가 아닌 대상을 공고마다 표기별 한 번씩이다. 호출하는 쪽의 트랜잭션 안에서 쓴다.
 */
@Component
@RequiredArgsConstructor
class ComplexCurrentSupplies {

    private final AnnouncementRepository announcements;
    private final AnnouncementSupplies supplies;

    /** current는 단지 상세의 현재 공고 목록 순서(게시일·ID 내림차순)다. 결과도 그 공고 순서, 공고 안에서는 공급표 순서다. */
    Result read(long complexId, List<CurrentAnnouncementRow> current) {
        List<Long> ids = current.stream().map(CurrentAnnouncementRow::announcementId).toList();
        if (ids.isEmpty()) {
            return new Result(List.of(), List.of(), Set.of());
        }
        Map<Long, Announcement> found = announcements.findAllById(ids).stream()
                .collect(Collectors.toMap(Announcement::getId, Function.identity()));
        Map<Long, AnnouncementSupplies.AnnouncementSupply> read = supplies.read(ids.stream().map(found::get).toList());
        List<CurrentSupplyConditionRow> conditions = new ArrayList<>();
        List<CurrentAnnouncementTargetRow> targets = new ArrayList<>();
        Set<Long> unconfirmed = new HashSet<>();
        for (CurrentAnnouncementRow announcement : current) {
            AnnouncementSupplies.AnnouncementSupply supply = read.get(announcement.announcementId());
            if (!supply.confirmed()) {
                unconfirmed.add(announcement.announcementId());
            }
            SupplySheet sheet = supply.sheet();
            Set<String> seenLabels = new HashSet<>();
            for (SupplySheet.Row row : sheet.rows()) {
                if (row.complex() == null || row.complex().getId() != complexId) {
                    continue;
                }
                boolean ownType = row.housingType() != null
                        && row.housingType().getHousingComplex().getId() == complexId;
                for (SupplySheet.Cell cell : sheet.cells(row.id())) {
                    if (!cell.everyone() && seenLabels.add(cell.label())) {
                        targets.add(new CurrentAnnouncementTargetRow(announcement.announcementId(), row.id(), cell.id(),
                                cell.label()));
                    }
                    if (ownType) {
                        sheet.lease(cell).ifPresent(lease -> conditions.add(new CurrentSupplyConditionRow(
                                announcement.announcementId(), row.id(), row.housingType().getId(), cell.id(),
                                cell.label(), lease.amounts().rentalDeposit(), lease.amounts().monthlyRent(),
                                lease.amounts().convertedDeposit(), announcement.postedDate(), supply.confirmed())));
                    }
                }
            }
        }
        return new Result(List.copyOf(conditions), List.copyOf(targets), Set.copyOf(unconfirmed));
    }

    /** unconfirmedAnnouncementIds는 공고문으로 확정하기 전이라 준비 데이터를 보인 공고다. */
    record Result(List<CurrentSupplyConditionRow> conditions, List<CurrentAnnouncementTargetRow> targets,
                  Set<Long> unconfirmedAnnouncementIds) {
    }
}
