package com.toadzip.backend.ingest.mapping.service;

import com.toadzip.backend.admin.exception.AdminDataConflictException;
import com.toadzip.backend.announcement.repository.SupplyRowRepository;
import com.toadzip.backend.ingest.exception.exception.InvalidIngestRequestException;
import com.toadzip.backend.ingest.mapping.dto.AnnouncementSupplyMatching.ExclusionRequest;
import com.toadzip.backend.ingest.mapping.dto.AnnouncementSupplyMatching.RefineRequest;
import com.toadzip.backend.ingest.mapping.dto.AnnouncementSupplyMatching.ReleaseRequest;
import com.toadzip.backend.ingest.mapping.dto.AnnouncementSupplyMatching.Row;
import com.toadzip.backend.ingest.mapping.dto.AnnouncementSupplyMatching.SplitRequest;
import com.toadzip.backend.ingest.mapping.dto.MyHomeAnnouncementMappingReport;
import com.toadzip.backend.ingest.mapping.repository.MyHomeAnnouncementMappingFailureStore;
import com.toadzip.backend.ingest.mapping.service.MyHomeAnnouncementMappingWriter.Correction;
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionScope;
import java.time.Clock;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 관리자 공급 매칭 보정 명령. 요청 검증은 보정 계획, 쓰기는 정제 Writer, 이력은 변경 이력이 맡고 여기서는 순서만 잇는다. */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnnouncementSupplyMatchingService {
    private final SupplyCorrectionSources sources;
    private final SupplyCorrectionPlanner planner;
    private final CorrectableAnnouncements targets;
    private final MyHomeAnnouncementMappingWriter writer;
    private final SavedSupplyMatches savedMatches;
    private final AnnouncementSupplyRowQueryService rowQuery;
    private final SupplyMatchingChangeLog changeLog;
    private final SupplyRowRepository productRows;
    private final MyHomeAnnouncementMappingFailureStore failureStore;
    private final Clock clock;

    @Transactional
    public MyHomeAnnouncementMappingReport refine(String identifier, RefineRequest request, String actor) {
        // POST 인터셉터가 트랜잭션 종료까지 전역 수집 잠금을 유지한다. 외부 API는 호출하지 않는다.
        IngestExecutionScope.verifyHeld();
        var planned = planner.refine(identifier, request, actor);
        var previous = targets.writablePrevious(identifier, planned.current(), planned.splitting());
        String rowsBefore = null;
        if (planned.splitting()) {
            rowsBefore = changeLog.snapshot(rowQuery.rows(identifier));
        }
        try {
            var report = writer.correct(planned.current(), previous, planned.correction()).report();
            var announcement = targets.written(identifier);
            if (request.rememberSelection()) {
                var selections = planned.correction().selections();
                for (var data : planned.current().data().supplyRows()) {
                    var match = selections.get(data.sourceSupplyRowIdentifier());
                    if (match == null) {
                        continue;
                    }
                    var before = savedMatches.view(data, false);
                    var saved = savedMatches.confirm(data, match.complex(), match.housingType(),
                            request.sourceUrl().strip(), request.reason().strip(), actor);
                    changeLog.savedMatch(announcement, data.sourceSupplyRowIdentifier(), before, saved, actor);
                }
            }
            if (planned.splitting()) {
                productRows.flush();
                changeLog.split(announcement, rowsBefore, rowQuery.rows(identifier), actor);
            }
            return report;
        }
        catch (MyHomeAnnouncementMappingRejectedException exception) {
            throw new InvalidIngestRequestException("정제 실패: " + exception.getMessage());
        }
    }

    /**
     * 선택 없이 저장한 매칭과 자동 매칭으로 이 공고만 다시 정제한다. 보정 결과 화면에서 오류가 풀렸는지 바로 확인하는 용도다.
     * 단건 정제라 매칭이나 LH 보강이 하나라도 실패하면 전체를 되돌린다. LH 보강 실패만은 전체 정제처럼 실패로 기록해
     * 고친 매칭 오류는 해결로 바꾸고 다음 원인을 실패 검토에 남긴다. 다른 실패는 기록을 바꾸지 않는다.
     */
    @Transactional
    public MyHomeAnnouncementMappingReport rerun(String identifier) {
        // POST 인터셉터가 트랜잭션 종료까지 전역 수집 잠금을 유지한다. 외부 API는 호출하지 않는다.
        IngestExecutionScope.verifyHeld();
        var current = sources.resolved(identifier);
        var currentSources = sources.views(identifier);
        var previous = targets.writablePrevious(identifier, current, false);
        try {
            return writer.correct(current, previous, Correction.NONE).report();
        }
        catch (LhEnrichmentRejectedMappingException exception) {
            var failures = currentSources.stream().map(source -> MyHomeAnnouncementSourceMapper.failureOf(
                    source, exception.reason(), exception.getMessage(), clock.instant())).toList();
            try {
                failureStore.reconcileRejectedAnnouncement(identifier, failures,
                        IngestExecutionScope.currentExecutionId().orElse(null));
            }
            catch (RuntimeException recordingFailure) {
                log.warn("단건 정제의 LH 보강 실패를 기록하지 못했습니다. identifier={}", identifier, recordingFailure);
                throw new InvalidIngestRequestException("정제 실패: " + exception.getMessage()
                        + " 저장은 되돌렸지만 오류 목록에는 기록하지 못했습니다.");
            }
            throw new InvalidIngestRequestException("정제 실패: " + exception.getMessage()
                    + " 저장은 되돌리고 오류 목록에 기록했습니다.");
        }
        catch (MyHomeAnnouncementMappingRejectedException exception) {
            throw new InvalidIngestRequestException("정제 실패: " + exception.getMessage() + " 오류 목록은 바꾸지 않았습니다.");
        }
    }

    @Transactional
    public List<Row> split(String identifier, SplitRequest request, String actor) {
        IngestExecutionScope.verifyHeld();
        SupplyMatchingEvidence.validate(request.sourceUrl(), request.reason());
        var current = sources.resolved(identifier);
        var data = sources.splitRow(current, request.rowIdentifier(), request.token());
        targets.verifySplittable(identifier, current);
        var plan = planner.split(current, data, request, actor);
        var previous = targets.previousOf(current);
        String before = changeLog.snapshot(rowQuery.rows(identifier));
        try {
            writer.correct(current, previous, Correction.split(plan));
        } catch (MyHomeAnnouncementMappingRejectedException exception) {
            throw new InvalidIngestRequestException("정제 실패: " + exception.getMessage());
        }
        // 공급행과 공고는 같은 영속성 컨텍스트라 한 번 flush로 둘 다 반영된다.
        productRows.flush();
        var result = rowQuery.rows(identifier);
        changeLog.split(targets.written(identifier), before, result, actor);
        return result;
    }

    @Transactional
    public List<Row> release(String identifier, ReleaseRequest request, String actor) {
        IngestExecutionScope.verifyHeld();
        sources.selectedRow(identifier, request.rowIdentifier(), request.token());
        var announcement = targets.locked(identifier).orElseThrow();
        var released = savedMatches.release(request.rowIdentifier())
                .orElseThrow(() -> new AdminDataConflictException("저장한 매칭이 없습니다. 다시 조회해 주세요."));
        changeLog.released(announcement, request.rowIdentifier(), released, request.reason().strip(), actor);
        return rowQuery.rows(identifier);
    }

    /** 등록 단지에 해당 주택형이 없는 원천 공급행을 근거와 함께 공개에서 빼거나 되돌린다. 다음 정제부터 반영된다. */
    @Transactional
    public List<Row> exclude(String identifier, ExclusionRequest request, String actor) {
        IngestExecutionScope.verifyHeld();
        sources.selectedRow(identifier, request.rowIdentifier(), request.token());
        var announcement = targets.locked(identifier)
                .orElseThrow(() -> new InvalidIngestRequestException("정제된 공고가 없습니다. 공고를 먼저 정제해 주세요."));
        var row = productRows.findAllByAnnouncement(announcement).stream()
                .filter(value -> request.rowIdentifier().equals(value.getSourceSupplyRowIdentifier())).findFirst()
                .orElseThrow(() -> new InvalidIngestRequestException("정제된 공급행이 없습니다. 공고를 먼저 정제해 주세요."));
        if (row.getSupplyGroup() != null) {
            throw new InvalidIngestRequestException("여러 공급행으로 나눈 원천은 PDF 보정에서 제외해 주세요.");
        }
        boolean before = row.isAdminExcluded();
        if (row.excludeByAdmin(request.excluded())) {
            changeLog.exclusion(announcement, request.rowIdentifier(), before, request.excluded(),
                    request.reason().strip(), actor);
        }
        return rowQuery.rows(identifier);
    }
}
