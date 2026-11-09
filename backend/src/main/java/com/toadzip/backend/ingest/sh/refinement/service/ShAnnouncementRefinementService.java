package com.toadzip.backend.ingest.sh.refinement.service;

import com.toadzip.backend.ingest.refinement.domain.BoardRefinementOutcome;
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionScope;
import com.toadzip.backend.ingest.refinement.domain.BoardRevisionName;
import com.toadzip.backend.ingest.refinement.service.BoardRevisionLinker;
import com.toadzip.backend.ingest.refinement.service.BoardRefinementAttempt;
import com.toadzip.backend.ingest.refinement.service.BoardRevisionTally;
import com.toadzip.backend.ingest.refinement.service.SourceRefinementRun;
import com.toadzip.backend.ingest.refinement.service.SourceRefinementRunner;
import com.toadzip.backend.ingest.sh.collection.domain.ShAnnouncementSource;
import com.toadzip.backend.ingest.sh.collection.repository.ShAnnouncementSourceRepository;
import com.toadzip.backend.ingest.sh.refinement.dto.ShAnnouncementRefinementReport;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * SH 공고 정제. 수집이 목록 한 페이지를 처리할 때마다 그 페이지의 원천만 등록일·게시글 번호 순으로 원천마다 한 트랜잭션에서
 * 미확정 공고로 정제한다. 같은 원천이면 두 번째 정제는 아무것도 바꾸지 않는다. 한 원천이 실패하면 그 원천만 되돌리고 다음 원천을
 * 계속한다.
 */
@Service
public class ShAnnouncementRefinementService {

    private final ShAnnouncementSourceRepository sources;
    private final ShAnnouncementRefiner refiner;
    private final SourceRefinementRunner runner;
    private final BoardRevisionLinker revisionLinker;

    public ShAnnouncementRefinementService(ShAnnouncementSourceRepository sources, ShAnnouncementRefiner refiner,
            SourceRefinementRunner runner, BoardRevisionLinker revisionLinker) {
        this.sources = sources;
        this.refiner = refiner;
        this.runner = runner;
        this.revisionLinker = revisionLinker;
    }

    /** 한 번의 실행에서 페이지마다 이어 정제하고 결과를 누적할 정제 실행을 시작한다. */
    public Run begin() {
        IngestExecutionScope.requireHeld();
        return new Run(runner.begin("SH 공고 정제", BoardRefinementOutcome.class));
    }

    public final class Run {

        private final SourceRefinementRun<BoardRefinementOutcome> run;
        private final BoardRevisionTally revisions = new BoardRevisionTally();
        /** 지금 정제 중인 원천의 집계. 커밋된 뒤에만 실행 집계에 합친다. */
        private BoardRefinementAttempt attempt;

        private Run(SourceRefinementRun<BoardRefinementOutcome> run) {
            this.run = run;
        }

        /** seqs는 수집한 게시글 번호다. 저장된 원천이 없는 번호는 건너뛴다. */
        public void refinePage(List<String> seqs) {
            run.refineFound("SH 페이지 " + seqs.getFirst(), () -> sources.findAllBySeqIn(seqs).stream()
                            .sorted(Comparator.comparing(ShAnnouncementSource::getRegisteredDate)
                                    .thenComparing(ShAnnouncementSource::getSeq))
                            .toList(),
                    ShAnnouncementSource::getSourceKey, source -> {
                        attempt = new BoardRefinementAttempt();
                        return refiner.refine(source, attempt);
                    }, (source, outcome) -> revisions.commit(source.getSourceKey(), attempt));
        }

        /** 정제 때 원공고를 찾지 못한 정정공고를, 이번 실행에서 원공고가 정제된 뒤 다시 잇는다. */
        public void finish() {
            revisionLinker.relinkUnlinked(run, revisions, BoardRevisionName::sh);
        }

        public ShAnnouncementRefinementReport report() {
            return new ShAnnouncementRefinementReport(run.targetCount(), run.count(BoardRefinementOutcome.CREATED),
                    run.count(BoardRefinementOutcome.UPDATED), run.count(BoardRefinementOutcome.UNCHANGED), run.count(BoardRefinementOutcome.REVIEW_REQUIRED),
                    run.count(BoardRefinementOutcome.TRASHED), revisions.linked(), revisions.unlinked(), run.failedCount(), run.failures());
        }
    }
}
