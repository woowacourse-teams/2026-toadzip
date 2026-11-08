package com.toadzip.backend.ingest.gh.refinement.service;

import com.toadzip.backend.ingest.refinement.domain.BoardRefinementOutcome;
import com.toadzip.backend.housing.repository.HousingComplexRepository;
import com.toadzip.backend.ingest.gh.collection.domain.GhAnnouncementSource;
import com.toadzip.backend.ingest.gh.collection.repository.GhAnnouncementSourceRepository;
import com.toadzip.backend.ingest.gh.refinement.dto.GhAnnouncementRefinementReport;
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionScope;
import com.toadzip.backend.ingest.refinement.domain.BoardRevisionName;
import com.toadzip.backend.ingest.refinement.service.BoardRevisionLinker;
import com.toadzip.backend.ingest.refinement.service.BoardRefinementAttempt;
import com.toadzip.backend.ingest.refinement.service.BoardRevisionTally;
import com.toadzip.backend.ingest.refinement.service.SourceRefinementRun;
import com.toadzip.backend.ingest.refinement.service.SourceRefinementRunner;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * GH 공고 정제. 수집이 목록 한 페이지를 처리할 때마다 그 페이지의 원천만 게시일·공고 번호 순으로 원천마다 한 트랜잭션에서
 * 미확정 공고로 정제한다. 같은 원천이면 두 번째 정제는 아무것도 바꾸지 않는다. 한 원천이 실패하면 그 원천만 되돌리고 다음 원천을
 * 계속한다.
 */
@Service
public class GhAnnouncementRefinementService {

    private final GhAnnouncementSourceRepository sources;
    private final HousingComplexRepository housingComplexes;
    private final GhAnnouncementRefiner refiner;
    private final SourceRefinementRunner runner;
    private final BoardRevisionLinker revisionLinker;

    public GhAnnouncementRefinementService(GhAnnouncementSourceRepository sources,
            HousingComplexRepository housingComplexes, GhAnnouncementRefiner refiner, SourceRefinementRunner runner,
            BoardRevisionLinker revisionLinker) {
        this.revisionLinker = revisionLinker;
        this.sources = sources;
        this.housingComplexes = housingComplexes;
        this.refiner = refiner;
        this.runner = runner;
    }

    /** 한 번의 실행에서 페이지마다 이어 정제하고 결과를 누적할 정제 실행을 시작한다. */
    public Run begin() {
        IngestExecutionScope.requireHeld();
        return new Run(runner.begin("GH 공고 정제", BoardRefinementOutcome.class));
    }

    public final class Run {

        private final SourceRefinementRun<BoardRefinementOutcome> run;
        private final BoardRevisionTally revisions = new BoardRevisionTally();
        private GhComplexIndex index;
        private int supplyRecheckRequired;
        /** 지금 정제 중인 원천의 집계. 커밋된 뒤에만 실행 집계에 합친다. */
        private BoardRefinementAttempt attempt;

        private Run(SourceRefinementRun<BoardRefinementOutcome> run) {
            this.run = run;
        }

        /** pbancNos는 수집한 공고 번호다. 저장된 원천이 없는 번호는 건너뛴다. */
        public void refinePage(List<String> pbancNos) {
            run.refineFound("GH 페이지 " + pbancNos.getFirst(), () -> {
                List<GhAnnouncementSource> targets = sources.findAllByPbancNoIn(pbancNos).stream()
                        .sorted(Comparator.comparing(GhAnnouncementSource::getPostedDate)
                                .thenComparing(GhAnnouncementSource::getPbancNo))
                        .toList();
                if (!targets.isEmpty()) {
                    prepareComplexIndex();
                }
                return targets;
            }, GhAnnouncementSource::getSourceKey, source -> {
                attempt = new BoardRefinementAttempt();
                return refiner.refine(source, index, attempt);
            }, (source, outcome) -> {
                revisions.commit(source.getSourceKey(), attempt);
                if (attempt.supplyRecheckRequired()) {
                    supplyRecheckRequired++;
                }
            });
        }

        /** 정제 때 원공고를 찾지 못한 정정공고를, 이번 실행에서 원공고가 정제된 뒤 다시 잇는다. */
        public void finish() {
            revisionLinker.relinkUnlinked(run, revisions, BoardRevisionName::gh);
        }

        /** 단지 색인은 실행당 한 번, 정제할 원천이 처음 나올 때 만든다. 원천을 읽는 트랜잭션 안에서 부른다. */
        private void prepareComplexIndex() {
            if (index == null) {
                index = GhComplexIndex.of(housingComplexes.findAll());
            }
        }

        public GhAnnouncementRefinementReport report() {
            return new GhAnnouncementRefinementReport(run.targetCount(), run.count(BoardRefinementOutcome.CREATED),
                    run.count(BoardRefinementOutcome.UPDATED), run.count(BoardRefinementOutcome.UNCHANGED), run.count(BoardRefinementOutcome.REVIEW_REQUIRED),
                    run.count(BoardRefinementOutcome.TRASHED), supplyRecheckRequired, revisions.linked(), revisions.unlinked(),
                    run.failedCount(), run.failures());
        }
    }
}
