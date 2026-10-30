package com.toadzip.backend.announcement.service;

import com.toadzip.backend.admin.domain.AdminDataChange;
import com.toadzip.backend.admin.exception.AdminDataConflictException;
import com.toadzip.backend.admin.repository.AdminDataChangeRepository;
import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.AnnouncementAttachment;
import com.toadzip.backend.announcement.domain.AnnouncementPublicationType;
import com.toadzip.backend.announcement.domain.AnnouncementSchedule;
import com.toadzip.backend.announcement.domain.ReceptionPlace;
import com.toadzip.backend.announcement.dto.correction.AnnouncementJsonCorrection.Apply;
import com.toadzip.backend.announcement.dto.correction.AnnouncementJsonCorrection.AttachmentChange;
import com.toadzip.backend.announcement.dto.correction.AnnouncementJsonCorrection.Change;
import com.toadzip.backend.announcement.dto.correction.AnnouncementJsonCorrection.Event;
import com.toadzip.backend.announcement.dto.correction.AnnouncementJsonCorrection.EventChange;
import com.toadzip.backend.announcement.dto.correction.AnnouncementJsonCorrection.Preview;
import com.toadzip.backend.announcement.dto.correction.AnnouncementJsonCorrection.Request;
import com.toadzip.backend.announcement.dto.correction.AnnouncementJsonCorrection.Selection;
import com.toadzip.backend.announcement.dto.correction.AnnouncementJsonCorrection.Workspace;
import com.toadzip.backend.announcement.dto.correction.SupplyCorrection;
import com.toadzip.backend.announcement.dto.request.AdminAnnouncementImportRequest.AttachmentRequest;
import com.toadzip.backend.announcement.dto.request.AdminAnnouncementImportRequest.ScheduleRequest;
import com.toadzip.backend.announcement.dto.request.AdminAnnouncementUpdateRequest;
import com.toadzip.backend.announcement.exception.AnnouncementNotFoundException;
import com.toadzip.backend.announcement.repository.AnnouncementAttachmentRepository;
import com.toadzip.backend.announcement.repository.AnnouncementRepository;
import com.toadzip.backend.announcement.repository.AnnouncementScheduleRepository;
import com.toadzip.backend.ingest.enrichment.repository.LhAnnouncementEnrichmentFailureStore;
import com.toadzip.backend.ingest.mapping.repository.MyHomeAnnouncementMappingFailureStore;
import jakarta.validation.Validator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class AnnouncementJsonCorrectionService {
    private static final Set<String> FIELDS = Set.of("name", "rentalType", "recruitmentType", "agencyCode",
            "postedDate", "applicationStartDate", "applicationEndDate", "winnerAnnouncementDate", "originalUrl",
            "receptionPlace.name", "receptionPlace.method", "receptionPlace.address", "receptionPlace.contact",
            "receptionPlace.url");
    private final AnnouncementRepository announcements;
    private final AnnouncementScheduleRepository schedules;
    private final AnnouncementAttachmentRepository attachments;
    private final AdminDataChangeRepository changes;
    private final AdminAnnouncementManagementService management;
    private final SupplyCorrectionService supplies;
    private final AdminAnnouncementImportJson importJson;
    private final MyHomeAnnouncementMappingFailureStore mappingFailures;
    private final LhAnnouncementEnrichmentFailureStore enrichmentFailures;
    private final ObjectMapper json;
    private final Validator validator;

    public Workspace workspace(long id) {
        find(id);
        var detail = management.detail(id);
        var supply = supplies.workspace(id);
        var events = schedules.findAllByAnnouncementIdIn(List.of(id)).stream()
                .map(event -> new Event(event.getId(), scheduleData(event), event.getComplexName())).toList();
        return new Workspace(hash(List.of(detail, supply.snapshotToken(), events)), detail, supply, events);
    }

    public Preview preview(long id, Request request) {
        return prepare(id, request).preview();
    }

    @Transactional
    public Workspace apply(long id, Apply apply, String actor) {
        var announcement = announcements.findByIdForUpdate(id).orElseThrow(AnnouncementNotFoundException::new);
        var request = apply.request();
        var plan = prepare(id, request);
        if (!plan.preview().confirmationToken().equals(apply.confirmationToken())) {
            throw new AdminDataConflictException("변경안이나 연결 후보가 달라졌습니다. 다시 미리보기 하세요.");
        }
        SupplyCorrection.Apply supplyApply = plan.preview().supply() == null ? null
                : new SupplyCorrection.Apply(request.correction(), plan.preview().supply().confirmationToken());
        if (supplyApply != null) {
            supplies.writeWithoutHistory(id, supplyApply, plan.preview().supply(), actor);
        }
        if (!plan.preview().fields().isEmpty()) {
            announcement.reviseByAdmin(incoming(announcement, plan.metadata()));
        }
        announcement.recordAdminSupplyChange();
        writeEvents(announcement, plan.preview().schedules());
        writeAttachments(announcement, plan.preview().attachments());
        announcements.flush();
        var after = workspace(id);
        // PDF 보정 한 번은 공급행·기본정보·일정 변경을 이력 한 건으로 남긴다.
        var audit = new LinkedHashMap<String, Object>(supplies.audit(after.supply(), request.correction()));
        audit.put("detail", after.detail());
        audit.put("events", after.events());
        audit.put("source", request.document().source());
        audit.put("fields", request.fields());
        var change = changes.saveAndFlush(new AdminDataChange("ANNOUNCEMENT", id, "CORRECT_FROM_PDF", actor,
                json.writeValueAsString(Map.of("detail", plan.before().detail(), "events", plan.before().events(),
                        "rows", plan.before().supply().rows())), json.writeValueAsString(audit)));
        if (supplyApply != null) {
            supplies.recordOperation(id, supplyApply, change.getId(), announcement.getVersion());
        }
        resolveFailures(announcement.getSourceAnnouncementIdentifier(), actor);
        return after;
    }

    /** PDF 보정은 공고를 원문 기준으로 맞추므로 이 공고에 남은 정제·보강 실패를 해결한 관리자와 함께 해결로 바꾼다. */
    private void resolveFailures(String identifier, String actor) {
        if (identifier == null || identifier.isBlank()) { return; }
        mappingFailures.resolveByAdmin(identifier, actor);
        enrichmentFailures.resolveByAdmin(identifier, actor);
    }

    private Prepared prepare(long id, Request request) {
        var before = workspace(id);
        if (!before.snapshotToken().equals(request.snapshotToken())) {
            throw new AdminDataConflictException("공고·공급정보·일정·첨부 또는 원천이 바뀌었습니다. 최신값을 다시 확인하세요.");
        }
        validateDocument(request);
        var fieldChanges = new ArrayList<Change>();
        var metadata = metadata(before.detail().data(), request, fieldChanges);
        find(id).validateAdminRevision(incoming(find(id), metadata));
        SupplyCorrection.Preview supply = null;
        if (!request.correction().newGroups().isEmpty()
                || !hash(before.supply().rows()).equals(hash(request.correction().rows()))) {
            supply = supplies.preview(id, before.supply(), request.correction());
        }
        if (supply == null && !before.supply().snapshotToken().equals(request.correction().snapshotToken())) {
            throw new AdminDataConflictException("공급정보가 바뀌었습니다. 다시 확인하세요.");
        }
        var eventChanges = eventChanges(id, request);
        var attachmentChanges = attachmentChanges(id, request);
        if (fieldChanges.isEmpty() && supply == null && eventChanges.isEmpty() && attachmentChanges.isEmpty()) {
            throw new IllegalArgumentException("적용할 변경이 없습니다. 현재값과 다른 항목을 선택하세요.");
        }
        var warnings = new ArrayList<String>();
        warnings.add("JSON에 없는 기존 행·조건·일정·첨부파일은 유지합니다. 새 공고·신규 공고 알림을 만들지 않습니다.");
        if (before.detail().scheduleReviewed()) {
            warnings.add("단지·순위별로 확인된 접수 일정은 유지합니다. JSON의 일반 일정은 해당 확인 기록을 대신하지 않습니다.");
        }
        if (request.document().unresolvedFields() != null && !request.document().unresolvedFields().isEmpty()) {
            warnings.add("추출 자료에 미확인 항목이 있습니다. 미확인 값은 적용하지 말고 원문을 다시 확인하세요.");
        }
        String supplyToken = "";
        if (supply != null) { supplyToken = supply.confirmationToken(); }
        String token = hash(List.of(request, fieldChanges, eventChanges, attachmentChanges, supplyToken));
        return new Prepared(before, metadata, new Preview(token, fieldChanges, supply,
                eventChanges, attachmentChanges, warnings));
    }

    private void validateDocument(Request request) {
        var document = request.document();
        if (!"admin-announcement-import/v1".equals(document.schemaVersion())) {
            throw new IllegalArgumentException("admin-announcement-import/v1 형식의 공고 JSON을 넣어 주세요.");
        }
        if (!importJson.isWithinSizeLimit(importJson.serialize(document))) {
            throw new IllegalArgumentException("공고 JSON은 UTF-8 기준 1,000,000바이트 이하여야 합니다.");
        }
        if (document.source() == null) { throw new IllegalArgumentException("공식 출처 source를 입력하세요."); }
        checkSize(document.supplyRows(), 200);
        checkSize(document.schedules(), 100);
        checkSize(document.attachments(), 100);
        checkSize(document.unresolvedFields(), 200);
    }

    private void checkSize(List<?> values, int maximum) {
        if (values != null && values.size() > maximum) {
            throw new IllegalArgumentException("공고 JSON 목록이 허용 개수를 초과합니다: " + maximum);
        }
    }

    private AdminAnnouncementUpdateRequest metadata(AdminAnnouncementUpdateRequest current, Request request,
            List<Change> differences) {
        var after = (ObjectNode) json.valueToTree(current);
        var document = json.valueToTree(request.document());
        var selected = new HashSet<String>();
        for (String field : request.fields()) {
            if (!FIELDS.contains(field) || !selected.add(field)) {
                throw new IllegalArgumentException("지원하지 않거나 중복된 기본정보 항목입니다: " + field);
            }
            String inputPath = "announcement." + field;
            if (field.startsWith("receptionPlace.")) { inputPath = field; }
            if (field.equals("originalUrl")) { inputPath = "source.originalUrl"; }
            var value = at(document, inputPath);
            if (value.isMissingNode() || value.isNull() || !value.isValueNode() || unresolved(request, inputPath)) {
                throw new IllegalArgumentException("미확인 항목은 적용할 수 없습니다: " + inputPath);
            }
            var previous = at(after, field);
            if (!previous.equals(value)) { differences.add(new Change(field, previous, value)); }
            put(after, field, value);
        }
        var result = json.treeToValue(after, AdminAnnouncementUpdateRequest.class);
        validate(result);
        return result;
    }

    private boolean unresolved(Request request, String path) {
        if (request.document().unresolvedFields() == null) { return false; }
        return request.document().unresolvedFields().stream().filter(Objects::nonNull).anyMatch(field -> {
            if (field.path() == null) { return false; }
            String unresolved = field.path().replaceFirst("^\\$\\.?", "");
            return path.equals(unresolved) || path.startsWith(unresolved + ".") || path.startsWith(unresolved + "[")
                    || unresolved.startsWith(path + ".") || unresolved.startsWith(path + "[");
        });
    }

    private JsonNode at(JsonNode node, String path) {
        for (var key : path.split("\\.")) { node = node.path(key); }
        return node;
    }

    private void put(ObjectNode root, String path, JsonNode value) {
        var parts = path.split("\\.");
        if (parts.length == 1) { root.set(path, value); return; }
        var child = root.get(parts[0]);
        if (!(child instanceof ObjectNode)) { child = root.putObject(parts[0]); }
        ((ObjectNode) child).set(parts[1], value);
    }

    private List<EventChange> eventChanges(long id, Request request) {
        var known = schedules.findAllByAnnouncementIdIn(List.of(id));
        var used = new HashSet<Long>(); var indexes = new HashSet<Integer>();
        var result = new ArrayList<EventChange>();
        for (var selection : request.schedules()) {
            var value = selected(request.document().schedules(), selection, indexes);
            if (unresolved(request, "schedules[" + selection.inputIndex() + "]")) {
                throw new IllegalArgumentException("미확인 일반 일정은 적용할 수 없습니다.");
            }
            validate(value);
            if (value.endAt().isBefore(value.startAt())) { throw new IllegalArgumentException("일정 종료가 시작보다 빠릅니다."); }
            ScheduleRequest previous = null;
            if (selection.existingId() != null) {
                var old = known.stream().filter(item -> item.getId().equals(selection.existingId())).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("현재 공고에 속한 일반 일정을 선택하세요."));
                if (!used.add(old.getId())) { throw new IllegalArgumentException("하나의 일정에 여러 입력을 적용할 수 없습니다."); }
                previous = scheduleData(old);
            }
            if (!value.equals(previous)) { result.add(new EventChange(selection.inputIndex(), selection.existingId(), previous, value)); }
        }
        return result;
    }

    private List<AttachmentChange> attachmentChanges(long id, Request request) {
        var known = attachments.findAllByAnnouncementIdIn(List.of(id));
        var used = new HashSet<Long>(); var indexes = new HashSet<Integer>();
        var result = new ArrayList<AttachmentChange>();
        for (var selection : request.attachments()) {
            var value = selected(request.document().attachments(), selection, indexes);
            if (unresolved(request, "attachments[" + selection.inputIndex() + "]")) {
                throw new IllegalArgumentException("미확인 첨부파일은 적용할 수 없습니다.");
            }
            validate(value);
            AttachmentRequest previous = null;
            if (selection.existingId() != null) {
                var old = known.stream().filter(item -> item.getId().equals(selection.existingId())).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("현재 공고에 속한 첨부파일을 선택하세요."));
                if (!used.add(old.getId())) { throw new IllegalArgumentException("하나의 첨부에 여러 입력을 적용할 수 없습니다."); }
                previous = new AttachmentRequest(old.getFileName(), old.getFileType(), old.getFileUrl());
            }
            if (!value.equals(previous)) { result.add(new AttachmentChange(selection.inputIndex(), selection.existingId(), previous, value)); }
        }
        return result;
    }

    private <T> T selected(List<T> values, Selection selection, Set<Integer> indexes) {
        if (values == null || selection.inputIndex() < 0 || selection.inputIndex() >= values.size()
                || !indexes.add(selection.inputIndex()) || values.get(selection.inputIndex()) == null) {
            throw new IllegalArgumentException("선택한 JSON 항목이 없거나 중복되었습니다.");
        }
        return values.get(selection.inputIndex());
    }

    private void validate(Object value) {
        var errors = validator.validate(value);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(errors.stream().map(error -> error.getPropertyPath() + ": "
                    + error.getMessage()).sorted().collect(java.util.stream.Collectors.joining(" / ")));
        }
    }

    private Announcement incoming(Announcement stored, AdminAnnouncementUpdateRequest request) {
        ReceptionPlace reception = null;
        if (request.receptionPlace() != null) {
            var place = request.receptionPlace();
            reception = ReceptionPlace.create(place.name(), place.method(), place.address(), place.contact(), place.url());
        }
        return Announcement.create(stored.getSourceAnnouncementIdentifier(), null, null, request.name(),
                AnnouncementPublicationType.ORIGINAL, request.rentalType(), request.recruitmentType(), request.agencyCode(),
                request.postedDate(), request.applicationStartDate(), request.applicationEndDate(),
                request.winnerAnnouncementDate(), request.originalUrl(), null, 0, reception);
    }

    private void writeEvents(Announcement announcement, List<EventChange> planned) {
        var current = schedules.findAllByAnnouncement(announcement);
        int order = current.stream().mapToInt(AnnouncementSchedule::getDisplayOrder).max().orElse(-1) + 1;
        for (var change : planned) {
            var value = change.after();
            var event = eventFor(announcement, current, change, order++);
            event.reviseByAdmin(value.scheduleType(), value.name(), value.startAt(), value.endAt());
        }
    }

    private void writeAttachments(Announcement announcement, List<AttachmentChange> planned) {
        var current = attachments.findAllByAnnouncement(announcement);
        int order = current.stream().mapToInt(AnnouncementAttachment::getDisplayOrder).max().orElse(-1) + 1;
        for (var change : planned) {
            var value = change.after();
            var attachment = attachmentFor(announcement, current, change, order++);
            attachment.reviseByAdmin(value.fileName(), value.fileType(), value.fileUrl());
        }
    }

    private ScheduleRequest scheduleData(AnnouncementSchedule event) {
        return new ScheduleRequest(event.getScheduleType(), event.getName(), event.getStartAt(), event.getEndAt());
    }

    private AnnouncementSchedule eventFor(Announcement announcement, List<AnnouncementSchedule> current,
            EventChange change, int order) {
        if (change.existingId() != null) {
            return current.stream().filter(item -> item.getId().equals(change.existingId())).findFirst().orElseThrow();
        }
        var value = change.after();
        return schedules.save(AnnouncementSchedule.create(announcement, value.scheduleType(), value.name(),
                value.startAt(), value.endAt(), order));
    }

    private AnnouncementAttachment attachmentFor(Announcement announcement, List<AnnouncementAttachment> current,
            AttachmentChange change, int order) {
        if (change.existingId() != null) {
            return current.stream().filter(item -> item.getId().equals(change.existingId())).findFirst().orElseThrow();
        }
        var value = change.after();
        return attachments.save(AnnouncementAttachment.create(announcement, value.fileName(), value.fileType(),
                value.fileUrl(), order));
    }

    private Announcement find(long id) {
        var value = announcements.findById(id).orElseThrow(AnnouncementNotFoundException::new);
        if (value.isAdminDeleted()) { throw new AdminDataConflictException("휴지통의 공고는 복구 후 갱신하세요."); }
        return value;
    }

    private String hash(Object value) { return importJson.hash(json.writeValueAsString(value)); }
    private record Prepared(Workspace before, AdminAnnouncementUpdateRequest metadata, Preview preview) { }
}
