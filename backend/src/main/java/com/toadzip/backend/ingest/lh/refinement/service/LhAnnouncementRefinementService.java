package com.toadzip.backend.ingest.lh.refinement.service;

import com.toadzip.backend.housing.repository.HousingComplexRepository;
import com.toadzip.backend.ingest.lh.collection.announcementcatalog.domain.LhAnnouncementCatalogSourceView;
import com.toadzip.backend.ingest.lh.collection.announcementcatalog.repository.LhAnnouncementCatalogSourceReader;
import com.toadzip.backend.ingest.lh.collection.service.LhAnnouncementCollectionCandidateResolver;
import com.toadzip.backend.ingest.lh.collection.service.LhAnnouncementCollectionCandidateResolver.Candidate;
import com.toadzip.backend.ingest.lh.refinement.domain.LhRevisionName;
import com.toadzip.backend.ingest.lh.refinement.dto.LhAnnouncementRefinementReport;
import com.toadzip.backend.ingest.lh.refinement.service.LhAnnouncementRefiner.Outcome;
import com.toadzip.backend.ingest.lh.refinement.service.LhAnnouncementRefiner.Tally;
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionScope;
import com.toadzip.backend.ingest.refinement.service.SourceRefinementRun;
import com.toadzip.backend.ingest.refinement.service.SourceRefinementRunner;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

/**
 * LH 공고 정제. 최신 공고목록의 임대 공고를 공고일·공고 ID 순(원공고 먼저)으로 공고마다 한 트랜잭션에서 정제한다.
 * 같은 입력이면 두 번째 실행은 아무것도 바꾸지 않는다. 한 공고가 실패하면 그 공고만 되돌리고 다음 공고를 계속하며, 실행 소유권을
 * 잃은 경우만은 실패로 삼키지 않고 바로 멈춘다. 전체 정제({@link #refineAll()})와, 수집이 요청 묶음을 마칠 때마다 그 공고만
 * 정제하는 묶음 정제({@link #begin()})가 같은 정제기를 쓴다.
 */
@Service
public class LhAnnouncementRefinementService {

    private final LhAnnouncementCatalogSourceReader catalog;
    private final HousingComplexRepository housingComplexes;
    private final LhAnnouncementRefiner refiner;
    private final SourceRefinementRunner runner;

    public LhAnnouncementRefinementService(LhAnnouncementCatalogSourceReader catalog,
            HousingComplexRepository housingComplexes, LhAnnouncementRefiner refiner, SourceRefinementRunner runner) {
        this.catalog = catalog;
        this.housingComplexes = housingComplexes;
        this.refiner = refiner;
        this.runner = runner;
    }

    public LhAnnouncementRefinementReport refineAll() {
        IngestExecutionScope.requireHeld();
        List<LhAnnouncementCatalogSourceView> targets = catalog.findAllPresentRentalHousingAnnouncements();
        IngestExecutionScope.beginWork("LH 공고 정제", "공고", targets.size());
        Run run = new Run(true);
        run.refine(targets);
        run.retryUnlinkedRevisions();
        return run.report();
    }

    /** 수집 실행 하나 동안 요청 묶음마다 이어 정제하고 결과를 누적할 정제 실행을 시작한다. */
    public Run begin() {
        IngestExecutionScope.requireHeld();
        return new Run(false);
    }

    public final class Run {

        private final SourceRefinementRun<Outcome> run;
        private final Tally tally = new Tally();
        private final Set<String> refinedPanIds = new HashSet<>();
        private LhComplexIndex index;
        /** 지금 정제 중인 공고의 집계. 커밋된 뒤에만 실행 집계에 합친다. */
        private Tally attempt;

        private Run(boolean reportProgress) {
            SourceRefinementRun<Outcome> started = runner.begin("LH 공고 정제", Outcome.class);
            run = reportProgress ? started.reportingProgress() : started;
        }

        /** 수집이 한 요청 묶음을 마치면, 그 묶음의 수집 대상(공고 ID와 목록 행)만 정제한다. */
        public void refineBundle(List<Candidate> bundle) {
            refineBundle(bundle.stream().map(Candidate::panId).distinct().toList(),
                    bundle.stream().map(candidate -> candidate.sourceAnnouncementKey().substring(
                            LhAnnouncementCollectionCandidateResolver.CATALOG_SOURCE_PREFIX.length())).toList());
        }

        /**
         * 요청 묶음에서 받은 최신 공고목록 행만 정제한다. 같은 공고 ID의 다른 조회 조건 행은 그 묶음을 다 받은 뒤 정제하므로
         * 아직 받지 못한 원천 위에서 정제하지 않는다.
         */
        public void refineBundle(Collection<String> panIds, Collection<String> catalogSourceKeys) {
            refineFound("LH 묶음 " + panIds.stream().findFirst().orElse(""),
                    () -> catalog.findAllByPanIdInAndPresentInLatestCatalogTrue(panIds).stream()
                            .filter(LhAnnouncementCatalogSourceView::isRentalHousingAnnouncement)
                            .filter(view -> catalogSourceKeys.contains(view.getSourceKey())).toList());
        }

        /**
         * 마무리: 이번 실행에서 목록 값이 바뀌었지만 묶음 수집 대상이 아니었던 공고(종료 정책 밖 공고)를 정제하고, 정제 때
         * 원공고를 찾지 못한 정정·취소공고를 원공고가 정제된 뒤 다시 잇는다. 수집 대상으로 골랐지만 묶음을 다 받지 못한
         * 공고는 정제하지 않고 이전 값을 유지한다.
         */
        public void finish(Instant changedSince, Collection<String> collectedPanIds) {
            Predicate<LhAnnouncementCatalogSourceView> changed = view -> view.getChangedAt() != null
                    && !view.getChangedAt().isBefore(changedSince);
            refineFound("LH 마무리 정제", () -> catalog.findAllPresentRentalHousingAnnouncements().stream()
                    .filter(view -> !refinedPanIds.contains(view.getPanId()))
                    .filter(view -> !collectedPanIds.contains(view.getPanId())).filter(changed).toList());
            retryUnlinkedRevisions();
        }

        private void retryUnlinkedRevisions() {
            Set<String> panIds = tally.unlinkedRevisionPanIds();
            if (panIds.isEmpty()) {
                return;
            }
            Tally relinked = new Tally();
            Set<String> retried = new HashSet<>();
            run.applyFound("LH 정정 재연결", () -> catalog.findAllByPanIdInAndPresentInLatestCatalogTrue(panIds).stream()
                            .filter(LhAnnouncementCatalogSourceView::isRentalHousingAnnouncement).toList(),
                    LhAnnouncementCatalogSourceView::getSourceKey, target -> {
                        attempt = new Tally();
                        refineWithIndex(target);
                        return true;
                    }, target -> {
                        relinked.add(attempt);
                        retried.add(target.getPanId());
                    });
            tally.addRelinked(relinked, retried);
        }

        /** 전체 정제: 이미 읽은 대상을 정제한다. */
        private void refine(List<LhAnnouncementCatalogSourceView> targets) {
            run.refine(ordered(targets), LhAnnouncementCatalogSourceView::getSourceKey, this::refineAttempt,
                    this::commit);
        }

        /** 정제할 대상을 읽는 일까지 원천 정제와 같은 규칙으로 돌려, 읽다 실패해도 이미 받은 수집 결과를 잃지 않는다. */
        private void refineFound(String lookupKey, Supplier<List<LhAnnouncementCatalogSourceView>> lookup) {
            run.refineFound(lookupKey, () -> ordered(lookup.get()), LhAnnouncementCatalogSourceView::getSourceKey,
                    this::refineAttempt, this::commit);
        }

        private List<LhAnnouncementCatalogSourceView> ordered(List<LhAnnouncementCatalogSourceView> targets) {
            List<LhAnnouncementCatalogSourceView> ordered = targets.stream()
                    .sorted(Comparator.comparing((LhAnnouncementCatalogSourceView view) -> view.noticeLocalDate()
                                    .orElse(LocalDate.MIN))
                            .thenComparing(LhAnnouncementRefinementService::isRevision)
                            .thenComparing(LhAnnouncementCatalogSourceView::getPanId)).toList();
            ordered.forEach(target -> refinedPanIds.add(target.getPanId()));
            return ordered;
        }

        private Outcome refineAttempt(LhAnnouncementCatalogSourceView target) {
            attempt = new Tally();
            return refineWithIndex(target);
        }

        private void commit(LhAnnouncementCatalogSourceView target, Outcome outcome) {
            tally.add(attempt);
            tally.count(outcome);
        }

        private Outcome refineWithIndex(LhAnnouncementCatalogSourceView target) {
            if (index == null) {
                index = LhComplexIndex.of(housingComplexes.findAll());
            }
            return refiner.refine(target, index, attempt);
        }

        public LhAnnouncementRefinementReport report() {
            return tally.toReport(run.targetCount(), run.failedCount());
        }
    }

    /** 같은 공고일이면 원공고를 먼저 정제해 정정·취소공고가 원공고와 그 공고 단지 연결을 찾게 한다. */
    private static boolean isRevision(LhAnnouncementCatalogSourceView view) {
        String name = view.getAnnouncementName();
        return name != null && new LhRevisionName(name).isRevision();
    }
}
