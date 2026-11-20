package com.toadzip.backend.announcement.service;

import com.toadzip.backend.announcement.dto.correction.AnnouncementJsonCorrection.*;
import com.toadzip.backend.announcement.dto.correction.SupplyCorrection;
import com.toadzip.backend.announcement.dto.correction.SupplySheetInput;
import com.toadzip.backend.announcement.dto.request.AdminAnnouncementImportRequest;
import com.toadzip.backend.announcement.dto.request.AdminAnnouncementImportRequest.*;
import com.toadzip.backend.announcement.domain.SupplyQuota;
import com.toadzip.backend.announcement.domain.SupplyTargetGroup;
import com.toadzip.backend.announcement.domain.RecruitmentType;
import com.toadzip.backend.housing.domain.RentalType;
import com.toadzip.backend.housing.repository.HousingComplexRepository;
import com.toadzip.backend.housing.repository.HousingTypeRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 기존 보정 미리보기에 전달할 변경안. 공급표가 이미 있거나 대상을 유일하게 연결할 수 없으면 운영자에게 남긴다. */
@Component
@RequiredArgsConstructor
class PublicationCorrection {
    private final ObjectMapper json;
    private final HousingComplexRepository complexes;
    private final HousingTypeRepository types;

    /** 워커가 쓴 추출 결과를 서버 보정 형식으로 읽는 경계다. 읽지 못한 값의 JDK·JSON 오류 문구는 운영자에게 보이지 않게 한다. */
    Request prepare(Workspace workspace, JsonNode tree, OffsetDateTime at, long jobId) {
        try {
            return read(workspace, tree, at, jobId);
        } catch (PublicationHold hold) {
            throw hold;
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
                && !metadata.winnerAnnouncementDate().isBefore(metadata.applicationEndDate()), "신청·발표 날짜 순서를 확인하세요.");
        List<ReceptionPlaceRequest> places = array(data, "receptionPlaces").stream()
                .map(item -> json.treeToValue(item, ReceptionPlaceRequest.class)).toList();
        List<ScheduleRequest> schedules = array(data, "schedules").stream()
                .map(item -> json.treeToValue(item, ScheduleRequest.class)).toList();
        var selects = new ArrayList<Selection>();
        require(schedules.containsAll(workspace.events().stream().map(Event::data).toList()),
                "기존 일정과 PDF 일정이 다릅니다.");
        for (int i = 0; i < schedules.size(); i++) {
            var event = schedules.get(i);
            require(Collections.frequency(schedules, event) == 1, "중복 일정의 적용 대상을 확인하세요.");
            if (workspace.events().stream().noneMatch(e -> e.data().equals(event))) {
                selects.add(new Selection(i, null));
            }
        }
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
        return new Request(workspace.snapshotToken(), document, fields, selects, List.of(), correction, List.of(), true);
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
            var links = workspace.supply().announcementComplexes().stream().filter(c -> name.equals(c.name())).toList();
            require(links.size() <= 1, "공고 단지 후보가 여러 곳입니다: " + name);
            var link = links.isEmpty() ? null : links.getFirst();
            Long complexId = link == null ? null : link.housingComplexId();
            if (link != null) { usedLinks.add(link.id()); }
            if (complexId == null) {
                var agency = workspace.detail().data().agencyCode();
                var found = complexes.findByNameAndProviderInAndAdminDeletedFalse(name, List.of(agency.name(), agency.displayName()));
                require(found.size() == 1, "기관·단지명이 일치하는 단지 한 곳을 확인하세요: " + name);
                complexId = found.getFirst().getId();
            }
            String type = row.required("name").asString();
            BigDecimal area = decimal(row, "exclusiveArea");
            var matches = types.findAllByHousingComplexIdIn(List.of(complexId)).stream().filter(t -> type.equals(t.getName())
                    && area != null && t.getExclusiveArea().compareTo(area) == 0).toList();
            require(matches.size() == 1 && seen.add(matches.getFirst().getId()), "주택형·전용면적 연결 확인: " + name + " " + type);
            require(row.path("houseTypes").isEmpty(), "여러 세부 주택타입의 연결을 확인하세요: " + type);
            String month = row.path("expectedMoveInMonth").asString(null);
            rows.add(new SupplySheetInput.HousingType(key(row, "key"), null, link == null ? null : link.id(), name,
                    complexId, matches.getFirst().getId(), null, type, area, decimal(row, "supplyArea"),
                    month == null ? null : YearMonth.parse(month), null, null, rows.size()));
        }
        require(!rows.isEmpty() && workspace.supply().announcementComplexes().stream().allMatch(c -> usedLinks.contains(c.id())),
                "공고 단지가 공급표에서 누락됐습니다.");
        var targets = new ArrayList<SupplySheetInput.Target>();
        for (var row : array(sheet, "targets")) {
            var groups = array(row, "groups").stream().map(g -> SupplyTargetGroup.valueOf(g.asString())).toList();
            targets.add(new SupplySheetInput.Target(key(row, "key"), null, row.required("label").asString(),
                    row.path("applicationCondition").asString(null), groups, targets.size()));
        }
        var cells = new ArrayList<SupplySheetInput.Cell>();
        for (var row : array(sheet, "cells")) {
            cells.add(new SupplySheetInput.Cell(cell(row), null, key(row, "housingType"), key(row, "target"), cells.size()));
        }
        var quotas = array(sheet, "quotas").stream().map(q -> new SupplySheetInput.Quota(
                SupplyQuota.Kind.valueOf(q.required("kind").asString()), q.required("count").asInt(),
                array(q, "members").stream().map(PublicationCorrection::cell).toList())).toList();
        require(!cells.isEmpty() && !quotas.isEmpty(), "공급대상·모집 수량이 없습니다.");
        var leases = array(sheet, "leaseTerms").stream().map(l -> new SupplySheetInput.Lease(decimal(l, "rentalDeposit"),
                decimal(l, "monthlyRent"), decimal(l, "convertedDeposit"), array(l, "members").stream()
                .map(PublicationCorrection::cell).toList())).toList();
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

    private static LocalDate date(JsonNode node, String field) { return LocalDate.parse(node.required(field).asString()); }
    private static String key(JsonNode node, String field) { return "new:" + node.required(field).asString(); }
    private static String cell(JsonNode node) { return key(node, "housingType") + "-" + node.required("target").asString(); }
    private static BigDecimal decimal(JsonNode node, String field) {
        String text = node.path(field).asString(null);
        return text == null ? null : new BigDecimal(text);
    }
}
