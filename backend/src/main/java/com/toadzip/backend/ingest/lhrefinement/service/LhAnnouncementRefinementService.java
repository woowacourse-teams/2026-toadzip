package com.toadzip.backend.ingest.lhrefinement.service;

import com.toadzip.backend.housing.repository.HousingComplexRepository;
import com.toadzip.backend.ingest.collection.lh.announcementcatalog.domain.LhAnnouncementCatalogSourceView;
import com.toadzip.backend.ingest.collection.lh.announcementcatalog.repository.LhAnnouncementCatalogSourceReader;
import com.toadzip.backend.ingest.lhrefinement.domain.LhRevisionName;
import com.toadzip.backend.ingest.lhrefinement.dto.LhAnnouncementRefinementReport;
import com.toadzip.backend.ingest.lhrefinement.service.LhAnnouncementRefiner.Outcome;
import com.toadzip.backend.ingest.lhrefinement.service.LhAnnouncementRefiner.Tally;
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionScope;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * LH 공고 정제(MAP_LH_ANNOUNCEMENTS). 최신 공고목록의 임대 공고를 공고일·공고 ID 순(원공고 먼저)으로 공고마다 한 트랜잭션에서 정제한다.
 * 같은 입력이면 두 번째 실행은 아무것도 바꾸지 않는다. 한 공고가 실패하면 그 공고만 되돌리고 다음 공고를 계속한다.
 */
@Slf4j
@Service
public class LhAnnouncementRefinementService {

    private final LhAnnouncementCatalogSourceReader catalog;
    private final HousingComplexRepository housingComplexes;
    private final LhAnnouncementRefiner refiner;
    private final TransactionTemplate transactions;

    public LhAnnouncementRefinementService(LhAnnouncementCatalogSourceReader catalog,
            HousingComplexRepository housingComplexes, LhAnnouncementRefiner refiner,
            PlatformTransactionManager transactionManager) {
        this.catalog = catalog;
        this.housingComplexes = housingComplexes;
        this.refiner = refiner;
        transactions = new TransactionTemplate(transactionManager);
        // 공고마다 따로 커밋해 한 공고의 실패가 앞서 정제한 공고를 되돌리지 않게 한다.
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public LhAnnouncementRefinementReport refineAll() {
        IngestExecutionScope.requireHeld();
        List<LhAnnouncementCatalogSourceView> targets = catalog.findAllPresentRentalHousingAnnouncements().stream()
                .sorted(Comparator.comparing((LhAnnouncementCatalogSourceView view) -> view.noticeLocalDate()
                                .orElse(LocalDate.MIN))
                        .thenComparing(LhAnnouncementRefinementService::isRevision)
                        .thenComparing(LhAnnouncementCatalogSourceView::getPanId))
                .toList();
        LhComplexIndex index = transactions.execute(status -> LhComplexIndex.of(housingComplexes.findAll()));
        IngestExecutionScope.beginWork("LH 공고 정제", "공고", targets.size());
        Tally tally = new Tally();
        int failed = 0;
        for (LhAnnouncementCatalogSourceView target : targets) {
            IngestExecutionScope.checkStopRequested();
            try {
                // 실패해 되돌린 공고의 집계는 남기지 않는다.
                Tally attempt = new Tally();
                Outcome outcome = transactions.execute(status -> refiner.refine(target, index, attempt));
                tally.add(attempt);
                tally.count(outcome);
            }
            catch (RuntimeException failure) {
                failed++;
                log.warn("LH 공고 정제에 실패했습니다. panId={}", target.getPanId(), failure);
            }
            IngestExecutionScope.workCompleted();
        }
        return tally.toReport(targets.size(), failed);
    }

    /** 같은 공고일이면 원공고를 먼저 정제해 정정·취소공고가 원공고와 그 공고 단지 연결을 찾게 한다. */
    private static boolean isRevision(LhAnnouncementCatalogSourceView view) {
        String name = view.getAnnouncementName();
        return name != null && new LhRevisionName(name).isRevision();
    }
}
