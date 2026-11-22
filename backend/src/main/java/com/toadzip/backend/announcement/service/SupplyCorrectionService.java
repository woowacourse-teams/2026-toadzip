package com.toadzip.backend.announcement.service;

import com.toadzip.backend.admin.domain.AdminDataChange;
import com.toadzip.backend.admin.exception.AdminDataConflictException;
import com.toadzip.backend.admin.exception.AdminDataStaleException;
import com.toadzip.backend.admin.repository.AdminDataChangeRepository;
import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.SupplyCorrectionRecord;
import com.toadzip.backend.announcement.domain.SupplyRow;
import com.toadzip.backend.announcement.dto.correction.SupplyCorrection;
import com.toadzip.backend.announcement.dto.correction.SupplySheetView;
import com.toadzip.backend.announcement.exception.AnnouncementNotFoundException;
import com.toadzip.backend.announcement.repository.AgencySupplyLineRepository;
import com.toadzip.backend.announcement.repository.AnnouncementAttachmentRepository;
import com.toadzip.backend.announcement.repository.AnnouncementComplexRepository;
import com.toadzip.backend.announcement.repository.AnnouncementRepository;
import com.toadzip.backend.announcement.repository.SupplyCorrectionRecordRepository;
import com.toadzip.backend.announcement.repository.SupplyRowRepository;
import com.toadzip.backend.announcement.repository.SupplySheetStore;
import com.toadzip.backend.announcement.repository.SupplySheetViewQueryRepository;
import com.toadzip.backend.housing.domain.HousingType;
import com.toadzip.backend.housing.domain.RentalType;
import com.toadzip.backend.housing.repository.HousingComplexRepository;
import com.toadzip.backend.housing.repository.HousingTypeRepository;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * 공고 공급표 보정(작업실·미리보기·적용). 요청은 저장할 공급표 전체이고 요청에 없는 개체는 지운다. 저장은 공급표 쓰기(SupplySheetStore)
 * 한 곳으로 하며, 관리자 수정 표시는 {@link SupplySheetProtection}의 작업대 규칙으로 정한다. PDF 보정(json-corrections)은 같은 계획·쓰기를
 * PDF 규칙으로 쓰고 이력은 그쪽이 한 건으로 남긴다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class SupplyCorrectionService {
    private final AnnouncementRepository announcements;
    private final SupplyRowRepository rows;
    private final SupplySheetViewQueryRepository sheetViews;
    private final SupplySheetPlanner planner;
    private final SupplySheetStore store;
    private final HousingComplexRepository complexes;
    private final HousingTypeRepository types;
    private final SupplyCorrectionRecordRepository corrections;
    private final AdminDataChangeRepository changes;
    private final AnnouncementComplexAdminLinks adminLinks;
    private final AnnouncementComplexRepository announcementComplexes;
    private final AgencySupplyLineRepository agencyLines;
    private final AnnouncementSupplies supplies;
    private final AnnouncementAttachmentRepository attachments;
    private final EntityManager entities;
    private final SameAddressComplexes sameAddress;
    private final ObjectMapper json;

    /**
     * 작업실. snapshotToken은 공급표·공고 단지 연결·원천 참고 행을 덮어, 화면이 본 뒤 그중 하나가 바뀌면 저장을 거절한다.
     * stagingToken은 확정 전 공고에서 '준비 데이터로 정본 시작'이 복사할 공급표(금액·대상·고른 주택타입 포함)를 덮는다. 정제가 준비
     * 데이터를 고쳐도 보정·연결 저장은 거절하지 않도록 두 토큰을 나눈다. 확정된 공고와 공고문 PDF가 있는 공고(PDF로 보정한다)는
     * stagingToken이 null이다.
     */
    public SupplyCorrection.Workspace workspace(long id) {
        var announcement = find(id);
        var sheet = sheetViews.read(id);
        var complexViews = announcementComplexes(announcement, sheet);
        var sourceReference = sourceReference(announcement);
        var names = sheet.housingTypes().stream().map(SupplySheetView.HousingType::name).distinct().toList();
        List<String> warnings = new ArrayList<>();
        sheet.housingTypes().stream().filter(type -> type.category() == null).forEach(type ->
                warnings.add(type.complexName() + " · " + type.name() + ": 공급 구분이 없습니다. 저장하려면 정해 주세요."));
        var draft = announcement.isSupplyConfirmed() || hasPdf(announcement) ? null : supplies.staging(announcement).draft();
        var stagingToken = draft == null ? null
                : hash(List.of(draft.housingTypes(), draft.targets(), draft.cells(), draft.quotas(), draft.leases()));
        return new SupplyCorrection.Workspace(hash(List.of(sheet, complexViews, sourceReference,
                announcement.isSupplyConfirmed())), stagingToken, announcement.getVersion(), sheet, complexViews, names,
                sourceReference, List.copyOf(warnings), announcement.isSupplyConfirmed());
    }

    /** 공고문 PDF가 있으면 정본은 PDF 보정으로만 만든다. 기관 준비 데이터 복사는 PDF 없는 공고의 대안이다. */
    private boolean hasPdf(Announcement announcement) {
        return attachments.findAllByAnnouncement(announcement).stream()
                .anyMatch(attachment -> attachment.getFileName().toLowerCase(Locale.ROOT).endsWith(".pdf"));
    }

    private List<SupplyCorrection.AnnouncementComplexView> announcementComplexes(Announcement announcement, SupplySheetView sheet) {
        return announcementComplexes.findAllByAnnouncementOrderByIdAsc(announcement).stream().map(complex -> {
            var linked = complex.linkedComplex();
            int rowCount = (int) sheet.housingTypes().stream()
                    .filter(type -> complex.getId().equals(type.announcementComplexId())).count();
            return new SupplyCorrection.AnnouncementComplexView(complex.getId(), complex.getSourceComplexName(),
                    complex.getRoadAddress(), linked == null ? null : linked.getId(), linked == null ? null : linked.getName(),
                    linked == null ? null : complex.getLinkMethod().name(), rowCount);
        }).toList();
    }

    /**
     * 기관(LH·GH) 준비 줄. 저장하지 않고 화면이 수량을 검산할 때만 쓴다. 준비 줄이 없는 공고(SH·등록 공고)는 비어 있다.
     */
    private List<SupplyCorrection.SourceReference> sourceReference(Announcement announcement) {
        return agencyLines.findAllByAnnouncementIdIn(List.of(announcement.getId())).stream()
                .map(line -> new SupplyCorrection.SourceReference(line.getComplexName(), line.getHousingTypeName(),
                        line.getExclusiveArea() == null ? null : SupplySheetViewQueryRepository.area(line.getExclusiveArea()),
                        line.getHouseholdCount()))
                .toList();
    }

    /**
     * 정본을 확정하지 않은 공고의 기관 준비 줄을 그대로 정본 공급표로 쓰고 확정한다. 정본은 PDF 보정·작업실 적용·등록으로만 생기므로,
     * 공고문 PDF가 없는 공고가 준비 데이터에서 정본을 시작할 때 쓴다. 화면이 본 뒤 공급정보가 바뀌었으면 거절한다.
     */
    @Transactional
    public SupplyCorrection.Workspace startFromStaging(long id, SupplyCorrection.StartFromStaging request, String actor) {
        var announcement = announcements.findByIdForUpdate(id).orElseThrow(AnnouncementNotFoundException::new);
        if (announcement.isAdminDeleted()) { throw new AdminDataConflictException("공고를 복구한 뒤 보정하세요."); }
        if (announcement.isSupplyConfirmed()) {
            throw new AdminDataConflictException("이미 공고 공급표가 있습니다. 공급정보 보정으로 고치세요.");
        }
        if (hasPdf(announcement)) {
            throw new AdminDataConflictException("공고문 PDF가 있는 공고입니다. PDF로 보정하세요.");
        }
        var current = workspace(id);
        if (!current.snapshotToken().equals(request.snapshotToken())
                || !request.stagingToken().equals(current.stagingToken())) {
            throw new AdminDataStaleException("공급정보가 바뀌었습니다. 최신 값을 다시 불러온 뒤 시작하세요.");
        }
        var staging = supplies.staging(announcement);
        if (staging.draft().housingTypes().isEmpty()) {
            throw new IllegalArgumentException("준비 데이터가 없는 공고입니다. 공급정보를 직접 입력하세요.");
        }
        store.replace(announcement, staging.draft());
        announcement.recordAdminSupplyChange();
        announcements.flush();
        var after = workspace(id);
        changes.save(new AdminDataChange("ANNOUNCEMENT", id, "START_SUPPLY_FROM_STAGING", actor,
                json.writeValueAsString(Map.of("sheet", current.sheet())),
                json.writeValueAsString(Map.of("sheet", after.sheet()))));
        return after;
    }

    /**
     * PDF 보정 단지 정하기의 같은 곳 기존 단지. addresses는 공고문 단지 개요 주소와 원천 도로명주소다. rentalType은 화면이 보낸
     * 공급유형으로, 화면은 관리자가 미확정 공고 공개에서 고른 공급유형이 있으면 그 값을, 없으면 저장된 공고 값을 보낸다. 보내지 않으면
     * 저장된 공고 값이다. 후보가 하나면 화면이 관리자 선택 없이 잇는다. renamable은 관리자가 고치거나 등록하지 않은 단지인지다.
     * 실제로 이름을 바꾸는지는 다른 공고의 단지명까지 보는 적용 미리보기가 정한다.
     */
    public List<SupplyCorrection.SameAddressComplex> sameAddressComplexes(long id, List<String> addresses,
            RentalType rentalType) {
        var announcement = find(id);
        var type = rentalType != null ? rentalType : announcement.getSupplyType();
        return sameAddress.find(addresses, type).stream().map(complex -> new SupplyCorrection.SameAddressComplex(
                complex.getId(), complex.getName(), complex.getAddress().getRoadAddress(),
                complex.acceptsAnnouncementName())).toList();
    }

    /**
     * 공고 단지 하나의 연결 단지를 관리자가 바꾼다(ADMIN). 그 공고 단지의 주택형도 새 단지로 옮긴다(AnnouncementComplexAdminLinks.relink).
     * 화면이 본 뒤 공급정보나 연결이 바뀌었으면 거절한다. 돌려주는 작업실은 옮긴 연결을 보인다.
     */
    @Transactional
    public SupplyCorrection.Workspace linkAnnouncementComplex(long id, long announcementComplexId,
            SupplyCorrection.ComplexLinkRequest request, String actor) {
        var announcement = find(id);
        if (!workspace(id).snapshotToken().equals(request.snapshotToken())) {
            throw new AdminDataStaleException("공급정보가 바뀌었습니다. 최신 값을 다시 불러온 뒤 연결하세요.");
        }
        adminLinks.relink(announcement, announcementComplexId, request.housingComplexId(), actor);
        announcements.flush();
        return workspace(id);
    }

    public SupplyCorrection.Preview preview(long id, SupplyCorrection.Request request) {
        var plan = plan(id, workspace(id), request, SupplySheetProtection.WORKBENCH);
        if (!plan.changed()) { throw new IllegalArgumentException("변경한 공급정보가 없습니다."); }
        return preview(plan, request);
    }

    /** 이미 읽은 작업실을 기준으로 요청을 저장할 공급표로 정한다. 작업실 토큰이 요청과 다르면 거절한다. */
    SupplySheetPlan plan(long id, SupplyCorrection.Workspace before, SupplyCorrection.Request request,
            SupplySheetProtection protection) {
        if (!before.snapshotToken().equals(request.snapshotToken())) {
            log.warn("event=announcement.supply_correction.conflict_detected announcementId={} check=snapshot "
                    + "requestToken={} currentToken={}", id, shortToken(request.snapshotToken()),
                    shortToken(before.snapshotToken()));
            throw new AdminDataStaleException("등록값이나 원천이 바뀌었습니다. 입력을 보관하고 최신 값과 다시 비교하세요.");
        }
        return planner.plan(find(id), before.sheet(), request.sheet(), protection);
    }

    /** 확인 토큰은 요청·저장할 공급표·공급대상 보호 표시·연결 후보 값을 덮는다. */
    SupplyCorrection.Preview preview(SupplySheetPlan plan, SupplyCorrection.Request request) {
        return new SupplyCorrection.Preview(hash(List.of(request, plan.after(), new TreeMap<>(plan.targetAdminModified()),
                plan.candidates())), plan.before(), plan.after(), plan.warnings());
    }

    @Transactional
    public SupplyCorrection.Applied apply(long id, SupplyCorrection.Apply request, String actor) {
        var announcement = announcements.findByIdForUpdate(id).orElseThrow(AnnouncementNotFoundException::new);
        String requestHash = hash(request);
        var previous = corrections.findByOperationId(request.correction().operationId());
        if (previous.isPresent()) {
            var applied = previous.get();
            if (applied.getAnnouncementId() != id || !applied.getRequestHash().equals(requestHash)) {
                throw new AdminDataConflictException("같은 작업 번호에 다른 변경 내용이 있습니다. 새 미리보기를 만드세요.");
            }
            return new SupplyCorrection.Applied(applied.getChangeId(), true, workspace(id));
        }
        var before = workspace(id);
        var plan = plan(id, before, request.correction(), SupplySheetProtection.WORKBENCH);
        if (!plan.changed()) { throw new IllegalArgumentException("변경한 공급정보가 없습니다."); }
        confirm(id, preview(plan, request.correction()), request);
        write(announcement, plan, request.correction(), actor);
        var after = workspace(id);
        var change = changes.saveAndFlush(new AdminDataChange("ANNOUNCEMENT", id, "CORRECT_SUPPLY", actor,
                json.writeValueAsString(Map.of("sheet", before.sheet())),
                json.writeValueAsString(audit(after, request.correction()))));
        recordOperation(id, request, change.getId(), announcement.getVersion());
        return new SupplyCorrection.Applied(change.getId(), false, after);
    }

    /**
     * 다른 보정 이력 한 건에 공급표 변경을 포함할 때 공급표만 저장한다. 호출자가 같은 잠금 트랜잭션에서 만든 계획을 쓴다.
     * 호출자는 같은 트랜잭션에서 이력을 남긴 뒤 {@link #recordOperation}으로 작업 번호를 그 이력에 연결한다.
     * 저장할 주택형 key(after)별로 연결한 단지 주택형 ID를 돌려준다(이 저장에서 만든 주택형 포함).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    Map<String, Long> writeWithoutHistory(long id, SupplySheetPlan plan, SupplyCorrection.Apply request, String actor) {
        var announcement = announcements.findByIdForUpdate(id).orElseThrow(AnnouncementNotFoundException::new);
        if (corrections.findByOperationId(request.correction().operationId()).isPresent()) {
            throw new AdminDataConflictException("이미 적용한 공급정보 보정 작업입니다. 최신값을 다시 확인하세요.");
        }
        confirm(id, preview(plan, request.correction()), request);
        return write(announcement, plan, request.correction(), actor);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void recordOperation(long id, SupplyCorrection.Apply request, long changeId, long announcementVersion) {
        corrections.save(new SupplyCorrectionRecord(request.correction().operationId(), id, hash(request),
                changeId, announcementVersion));
    }

    /** 공급표 보정 이력의 변경 후 값. 다른 보정 이력에 포함할 때도 같은 필드를 남긴다. */
    public Map<String, Object> audit(SupplyCorrection.Workspace after, SupplyCorrection.Request correction) {
        var audit = new LinkedHashMap<String, Object>();
        audit.put("sheet", after.sheet());
        audit.put("reason", correction.reason().strip());
        audit.put("sourceUrl", correction.sourceUrl());
        audit.put("sourcePage", correction.sourcePage());
        audit.put("operationId", correction.operationId());
        return audit;
    }

    private void confirm(long id, SupplyCorrection.Preview preview, SupplyCorrection.Apply request) {
        if (!preview.confirmationToken().equals(request.confirmationToken())) {
            log.warn("event=announcement.supply_correction.conflict_detected announcementId={} check=confirmation "
                    + "requestToken={} currentToken={}", id, shortToken(request.confirmationToken()),
                    shortToken(preview.confirmationToken()));
            throw new AdminDataStaleException("미리보기 이후 선택한 단지·주택형 또는 변경안이 바뀌었습니다. 다시 확인하세요.");
        }
    }

    /**
     * 계획한 공급표를 쓴다. 관리자가 제외해 보이지 않던 옛 공급행은 먼저 지운다. 남겨 두면 같은 이름의 새 주택형이 그 행을 자연 키로
     * 차지해 다시 보이지 않게 된다. 공급표 쓰기는 관리자 수정 표시를 다루지 않으므로 쓴 뒤 주택형마다 계획한 표시로 맞춘다.
     */
    private Map<String, Long> write(Announcement announcement, SupplySheetPlan plan, SupplyCorrection.Request request,
            String actor) {
        announcement.recordAdminSupplyChange();
        announcements.flush();
        rows.deleteAll(rows.findAllByAnnouncement(announcement).stream().filter(SupplyRow::isAdminExcluded).toList());
        rows.flush();
        var created = createTypes(plan.after(), request);
        var announcementComplexIds = adminLinks.ensure(announcement, plan, actor);
        var saved = store.replace(announcement, plan.draft(created, announcementComplexIds));
        Map<String, SupplyRow> written = new LinkedHashMap<>();
        saved.housingTypes().forEach((key, rowId) -> written.put(key, rows.findById(rowId).orElseThrow()));
        adminLinks.apply(plan.links(), written, actor);
        adminLinks.unlinkEmptied(announcement, plan, announcementComplexIds, actor);
        announcements.flush();
        List<Long> modified = new ArrayList<>();
        List<Long> unmodified = new ArrayList<>();
        plan.after().housingTypes().forEach(type -> (type.adminModified() ? modified : unmodified)
                .add(saved.housingTypes().get(type.key())));
        if (!modified.isEmpty()) { rows.markAdminModified(modified, true); }
        if (!unmodified.isEmpty()) { rows.markAdminModified(unmodified, false); }
        written.values().forEach(entities::refresh);
        Map<String, Long> typeIds = new LinkedHashMap<>();
        plan.after().housingTypes().forEach(type -> {
            Long typeId = type.housingTypeId() != null ? type.housingTypeId() : created.get(type.key());
            if (typeId != null) { typeIds.put(type.key(), typeId); }
        });
        return typeIds;
    }

    /** 새 주택형은 한 저장에서 같은 단지·이름이면 주택형이 달라도 하나만 만들어 함께 쓴다(계획이 면적이 같음을 확인한다). */
    private Map<String, Long> createTypes(SupplySheetView after, SupplyCorrection.Request request) {
        Map<String, HousingType> byName = new HashMap<>();
        Map<String, Long> result = new HashMap<>();
        for (var type : after.housingTypes()) {
            var value = type.newHousingType();
            if (value == null) { continue; }
            var housingType = byName.computeIfAbsent(SupplySheetPlanner.newTypeKey(type), key -> types.save(
                    HousingType.createFromAnnouncement(complexes.findById(type.housingComplexId()).orElseThrow(),
                            "MANUAL:" + request.operationId() + ":" + type.key(), value.name(), value.exclusiveArea(),
                            value.supplyArea(), null)));
            result.put(type.key(), housingType.getId());
        }
        return result;
    }

    private Announcement find(long id) {
        var result = announcements.findById(id).orElseThrow(AnnouncementNotFoundException::new);
        if (result.isAdminDeleted()) { throw new AdminDataConflictException("공고를 복구한 뒤 보정하세요."); }
        return result;
    }

    private static String shortToken(String token) {
        return token == null ? null : token.substring(0, Math.min(12, token.length()));
    }

    private String hash(Object value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(json.writeValueAsString(value).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
