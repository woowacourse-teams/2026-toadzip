package com.toadzip.backend.announcement.service;

import com.toadzip.backend.announcement.dto.correction.AnnouncementJsonCorrection.Event;
import com.toadzip.backend.announcement.dto.correction.AnnouncementJsonCorrection.Request;
import com.toadzip.backend.announcement.dto.correction.AnnouncementJsonCorrection.Selection;
import com.toadzip.backend.announcement.dto.correction.AnnouncementJsonCorrection.Workspace;
import com.toadzip.backend.announcement.dto.correction.SupplyCorrection;
import com.toadzip.backend.announcement.dto.correction.SupplySheetInput;
import com.toadzip.backend.announcement.dto.request.AdminAnnouncementImportRequest;
import com.toadzip.backend.announcement.dto.request.AdminAnnouncementImportRequest.AnnouncementRequest;
import com.toadzip.backend.announcement.dto.request.AdminAnnouncementImportRequest.ReceptionPlaceRequest;
import com.toadzip.backend.announcement.dto.request.AdminAnnouncementImportRequest.ScheduleRequest;
import com.toadzip.backend.announcement.dto.request.AdminAnnouncementImportRequest.SourceRequest;
import com.toadzip.backend.announcement.domain.SupplyQuota;
import com.toadzip.backend.announcement.domain.SupplyTargetGroup;
import com.toadzip.backend.announcement.domain.RecruitmentType;
import com.toadzip.backend.announcement.domain.ScheduleType;
import com.toadzip.backend.housing.domain.RentalType;
import com.toadzip.backend.housing.repository.HousingComplexRepository;
import com.toadzip.backend.housing.repository.HousingTypeRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 기존 보정 미리보기에 전달할 변경안. 공급표가 이미 있거나 대상을 유일하게 연결할 수 없으면 운영자에게 남긴다. */
@Component
@RequiredArgsConstructor
class PublicationCorrection {
    private final ObjectMapper json;
    private final HousingComplexRepository complexes;
    private final HousingTypeRepository types;

    /**
     * 워커가 쓴 추출 결과를 서버 보정 형식으로 읽는 경계다. 읽지 못한 값의 JDK·JSON 오류 문구는 운영자에게 보이지 않게 한다.
     * 단지·주택형을 찾는 조회의 DB 실패는 추출 결과의 문제가 아니므로 감싸지 않는다. 일시 오류면 다음 주기에 다시 판정한다.
     */
    Request prepare(Workspace workspace, JsonNode tree, OffsetDateTime at, long jobId) {
        try {
            return read(workspace, tree, at, jobId);
        } catch (PublicationHold | DataAccessException | TransactionException passed) {
            throw passed;
        } catch (RuntimeException unreadable) {
            throw new PublicationHold("추출 결과를 보정안으로 바꾸지 못했습니다. 원문과 추출값을 확인하세요.", unreadable);
        }
    }

    private Request read(Workspace workspace, JsonNode tree, OffsetDateTime at, long jobId) {
        var current = workspace.detail().data();
        var review = tree.path("publicationReview");
        var data = tree.path("data");
        var notice = data.path("announcement");
        var metadata = new AnnouncementRequest(current.name(),
                judged(RentalType.class, review, "rentalType", "임대유형 판정값을 확인할 수 없습니다."),
                judged(RecruitmentType.class, review, "recruitmentType", "모집유형 판정값을 확인할 수 없습니다."),
                current.agencyCode(), current.postedDate(), date(notice, "applicationStartDate"),
                date(notice, "applicationEndDate"), date(notice, "winnerAnnouncementDate"));
        require(current.postedDate() != null && current.postedDate().equals(date(notice, "postedDate")),
                "수집한 공고일과 PDF 모집공고일이 다릅니다.");
        require(!metadata.applicationEndDate().isBefore(metadata.applicationStartDate())
                && !metadata.winnerAnnouncementDate().isBefore(metadata.applicationEndDate()),
                "신청·발표 날짜 순서를 확인하세요.");
        List<ReceptionPlaceRequest> places = array(data, "receptionPlaces").stream()
                .map(item -> json.treeToValue(item, ReceptionPlaceRequest.class)).toList();
        List<ScheduleRequest> schedules = array(data, "schedules").stream()
                .map(item -> json.treeToValue(item, ScheduleRequest.class)).toList();
        var selects = newSchedules(schedules, workspace.events());
        var source = new SourceRequest(current.originalUrl(), "pdf-job-" + jobId, at);
        var document = new AdminAnnouncementImportRequest("admin-announcement-import/v1", source, metadata, places,
                null, List.of(), schedules, List.of(), List.of());
        var sheet = sheet(workspace, data.path("supplySheet"));
        var correction = new SupplyCorrection.Request(UUID.randomUUID(), workspace.supply().snapshotToken(),
                "JEV 원문 대조 통과 · PDF 추출 #" + jobId, current.originalUrl(), null, sheet);
        var fields = new ArrayList<>(List.of("applicationStartDate", "applicationEndDate", "winnerAnnouncementDate",
                "rentalType", "recruitmentType"));
        if (!places.isEmpty() || !current.receptionPlaces().isEmpty()) {
            fields.add("receptionPlaces");
        }
        return new Request(workspace.snapshotToken(), document, fields, selects, List.of(), correction, List.of(),
                true);
    }

    /**
     * 수집한 일정은 이름이 고정이고 시각이 없어 공고문 일정과 글자 그대로 같을 수 없다. 그래서 기존 일정은 고치거나 지우지 않고,
     * 공고문 일정 가운데 기존 일정과 같은 일정이 아닌 것만 새 일정으로 더한다. 공고문에 똑같이 여러 번 적힌 일정은 한 번만 더한다.
     * 접수 기간과 발표일은 워커가 따로 확인하므로 일정 목록이 달라도 보류하지 않는다.
     */
    private static List<Selection> newSchedules(List<ScheduleRequest> schedules, List<Event> existing) {
        var selections = new ArrayList<Selection>();
        var added = new HashSet<ScheduleRequest>();
        for (int index = 0; index < schedules.size(); index++) {
            ScheduleRequest schedule = schedules.get(index);
            boolean known = existing.stream().anyMatch(event -> sameSchedule(event.data(), schedule));
            if (!known && added.add(schedule)) {
                selections.add(new Selection(index, null));
            }
        }
        return selections;
    }

    /** 같은 일정은 종류가 같거나 한쪽이 기타이고, 시각을 뺀 날짜 구간이 하루라도 겹치는 일정이다. */
    private static boolean sameSchedule(ScheduleRequest existing, ScheduleRequest fromPdf) {
        boolean comparableType = existing.scheduleType() == fromPdf.scheduleType()
                || existing.scheduleType() == ScheduleType.ETC || fromPdf.scheduleType() == ScheduleType.ETC;
        boolean overlapping = !fromPdf.startAt().toLocalDate().isAfter(existing.endAt().toLocalDate())
                && !existing.startAt().toLocalDate().isAfter(fromPdf.endAt().toLocalDate());
        return comparableType && overlapping;
    }

    private SupplySheetInput sheet(Workspace workspace, JsonNode sheet) {
        require(!workspace.supply().supplyConfirmed() && workspace.supply().sheet().housingTypes().isEmpty(),
                "기존 공급표가 있어 교체·삭제 검토가 필요합니다.");
        var rows = new ArrayList<SupplySheetInput.HousingType>();
        var seen = new HashSet<Long>();
        var usedLinks = new HashSet<Long>();
        var housingTypes = array(sheet, "housingTypes");
        Optional<String> onlyComplex = onlyLinkedComplex(workspace, housingTypes);
        for (var row : housingTypes) {
            String name = onlyComplex.orElseGet(() -> row.required("complexName").asString());
            var links = workspace.supply().announcementComplexes().stream().filter(c -> name.equals(c.name()))
                    .toList();
            require(links.size() <= 1, "공고 단지 후보가 여러 곳입니다: " + name);
            Long linkId = null;
            Long complexId = null;
            if (!links.isEmpty()) {
                linkId = links.getFirst().id();
                complexId = links.getFirst().housingComplexId();
                usedLinks.add(linkId);
            }
            if (complexId == null) {
                var agency = workspace.detail().data().agencyCode();
                var found = complexes.findByNameAndProviderInAndAdminDeletedFalse(name,
                        List.of(agency.name(), agency.displayName()));
                require(found.size() == 1, "기관·단지명이 일치하는 단지 한 곳을 확인하세요: " + name);
                complexId = found.getFirst().getId();
            }
            String type = row.required("name").asString();
            BigDecimal area = decimal(row, "exclusiveArea");
            var matches = types.findAllByHousingComplexIdIn(List.of(complexId)).stream()
                    .filter(t -> type.equals(t.getName()) && area != null && t.getExclusiveArea().compareTo(area) == 0)
                    .toList();
            require(matches.size() == 1 && seen.add(matches.getFirst().getId()),
                    "주택형·전용면적 연결 확인: " + name + " " + type);
            require(row.path("houseTypes").isEmpty(), "여러 세부 주택타입의 연결을 확인하세요: " + type);
            YearMonth month = yearMonth(row, "expectedMoveInMonth");
            rows.add(new SupplySheetInput.HousingType(key(row, "key"), null, linkId, name,
                    complexId, matches.getFirst().getId(), null, type, area, decimal(row, "supplyArea"),
                    month, null, null, rows.size()));
        }
        boolean everyComplexUsed = workspace.supply().announcementComplexes().stream()
                .allMatch(c -> usedLinks.contains(c.id()));
        require(!rows.isEmpty() && everyComplexUsed, "공고 단지가 공급표에서 누락됐습니다.");
        var targets = new ArrayList<SupplySheetInput.Target>();
        for (var row : array(sheet, "targets")) {
            var groups = array(row, "groups").stream().map(g -> SupplyTargetGroup.valueOf(g.asString())).toList();
            targets.add(new SupplySheetInput.Target(key(row, "key"), null, row.required("label").asString(),
                    row.path("applicationCondition").asString(null), groups, targets.size()));
        }
        var cells = new ArrayList<SupplySheetInput.Cell>();
        for (var row : array(sheet, "cells")) {
            cells.add(new SupplySheetInput.Cell(cell(row), null, key(row, "housingType"), key(row, "target"),
                    cells.size()));
        }
        var quotas = array(sheet, "quotas").stream().map(q -> new SupplySheetInput.Quota(
                SupplyQuota.Kind.valueOf(q.required("kind").asString()), q.required("count").asInt(),
                array(q, "members").stream().map(PublicationCorrection::cell).toList())).toList();
        require(!cells.isEmpty() && !quotas.isEmpty(), "공급대상·모집 수량이 없습니다.");
        var leases = array(sheet, "leaseTerms").stream().map(l -> new SupplySheetInput.Lease(
                decimal(l, "rentalDeposit"), decimal(l, "monthlyRent"), decimal(l, "convertedDeposit"),
                array(l, "members").stream().map(PublicationCorrection::cell).toList())).toList();
        return new SupplySheetInput(rows, targets, cells, quotas, leases);
    }

    /**
     * 단지 열이 없는 공급표(단지 한 곳만 모집하는 공고)는 모든 주택형의 단지명이 빈다. 공고 단지가 한 곳일 때만 그 이름을 쓰고,
     * 없거나 여러 곳이면 어느 단지인지 알 수 없어 보류한다. 단지명이 하나라도 적혀 있으면 채우지 않는다.
     */
    private static Optional<String> onlyLinkedComplex(Workspace workspace, List<JsonNode> housingTypes) {
        boolean named = housingTypes.stream().anyMatch(row -> !row.required("complexName").asString().isBlank());
        if (named || housingTypes.isEmpty()) {
            return Optional.empty();
        }
        var linked = workspace.supply().announcementComplexes();
        require(linked.size() == 1, "공급표에 단지명이 없고 공고에 연결된 단지가 한 곳이 아닙니다(" + linked.size() + "곳).");
        return Optional.of(linked.getFirst().name());
    }

    static void require(boolean ok, String reason) {
        if (!ok) {
            throw new PublicationHold(reason);
        }
    }

    static List<JsonNode> array(JsonNode node, String field) {
        require(node.path(field).isArray(), field + " 목록을 확인할 수 없습니다.");
        return node.path(field).valueStream().toList();
    }

    /** 워커가 원문 대조를 통과시킨 유형. 없거나 서버가 모르는 값이면 보류한다. */
    private static <E extends Enum<E>> E judged(Class<E> kind, JsonNode review, String field, String missing) {
        String name = review.path(field).asString("");
        return Arrays.stream(kind.getEnumConstants()).filter(value -> value.name().equals(name)).findFirst()
                .orElseThrow(() -> new PublicationHold(missing));
    }

    private static LocalDate date(JsonNode node, String field) {
        return LocalDate.parse(node.required(field).asString());
    }

    private static String key(JsonNode node, String field) {
        return "new:" + node.required(field).asString();
    }

    private static String cell(JsonNode node) {
        return key(node, "housingType") + "-" + node.required("target").asString();
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        String text = node.path(field).asString(null);
        if (text == null) {
            return null;
        }
        return new BigDecimal(text);
    }

    private static YearMonth yearMonth(JsonNode node, String field) {
        String text = node.path(field).asString(null);
        if (text == null) {
            return null;
        }
        return YearMonth.parse(text);
    }
}
