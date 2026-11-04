package com.toadzip.backend.ingest.sh.refinement.service;

import com.toadzip.backend.ingest.pipeline.repository.IngestOwnershipLostException;
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionScope;
import com.toadzip.backend.ingest.pipeline.service.IngestWriteOwnershipGuard;
import com.toadzip.backend.ingest.sh.collection.domain.ShAnnouncementSource;
import com.toadzip.backend.ingest.sh.collection.repository.ShAnnouncementSourceRepository;
import com.toadzip.backend.ingest.sh.refinement.dto.ShAnnouncementRefinementReport;
import com.toadzip.backend.ingest.sh.refinement.service.ShAnnouncementRefiner.Outcome;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * SH 공고 정제. 저장된 SH 원천을 등록일·게시글 번호 순으로 원천마다 한 트랜잭션에서 미확정 공고로 정제한다. 같은 원천이면 두 번째
 * 실행은 아무것도 바꾸지 않는다. 한 원천이 실패하면 그 원천만 되돌리고 다음 원천을 계속한다.
 */
@Slf4j
@Service
public class ShAnnouncementRefinementService {

    private final ShAnnouncementSourceRepository sources;
    private final ShAnnouncementRefiner refiner;
    private final TransactionTemplate transactions;
    private final IngestWriteOwnershipGuard ownershipGuard;

    public ShAnnouncementRefinementService(ShAnnouncementSourceRepository sources, ShAnnouncementRefiner refiner,
            PlatformTransactionManager transactionManager, IngestWriteOwnershipGuard ownershipGuard) {
        this.sources = sources;
        this.refiner = refiner;
        this.ownershipGuard = ownershipGuard;
        transactions = new TransactionTemplate(transactionManager);
        // 원천마다 따로 커밋해 한 원천의 실패가 앞서 정제한 공고를 되돌리지 않게 한다.
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public ShAnnouncementRefinementReport refineAll() {
        IngestExecutionScope.requireHeld();
        List<ShAnnouncementSource> targets = sources.findAll().stream()
                .sorted(Comparator.comparing(ShAnnouncementSource::getRegisteredDate)
                        .thenComparing(ShAnnouncementSource::getSeq))
                .toList();
        IngestExecutionScope.beginWork("SH 공고 정제", "게시글", targets.size());
        Map<Outcome, Integer> outcomes = new EnumMap<>(Outcome.class);
        int failed = 0;
        for (ShAnnouncementSource target : targets) {
            IngestExecutionScope.checkStopRequested();
            try {
                outcomes.merge(transactions.execute(status -> {
                    ownershipGuard.verifyWrite();
                    return refiner.refine(target);
                }), 1, Integer::sum);
            }
            catch (IngestOwnershipLostException lost) {
                throw lost;
            }
            catch (RuntimeException failure) {
                failed++;
                log.warn("SH 공고 정제에 실패했습니다. sourceKey={}", target.getSourceKey(), failure);
            }
            IngestExecutionScope.workCompleted();
        }
        return new ShAnnouncementRefinementReport(targets.size(), count(outcomes, Outcome.CREATED),
                count(outcomes, Outcome.UPDATED), count(outcomes, Outcome.UNCHANGED),
                count(outcomes, Outcome.REVIEW_REQUIRED), count(outcomes, Outcome.TRASHED), failed);
    }

    private static int count(Map<Outcome, Integer> outcomes, Outcome outcome) {
        return outcomes.getOrDefault(outcome, 0);
    }
}
