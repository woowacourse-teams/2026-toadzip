package com.toadzip.backend.ingest.mapping.service;

import static com.toadzip.backend.ingest.mapping.service.ComplexMergeChecks.conflict;
import static com.toadzip.backend.ingest.mapping.service.ComplexMergeChecks.require;

import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.repository.HousingComplexRepository;
import com.toadzip.backend.ingest.mapping.domain.MyHomeComplexMerge;
import com.toadzip.backend.ingest.mapping.dto.ComplexMergePreviewRequest;
import com.toadzip.backend.ingest.mapping.dto.ComplexMergePreviewResponse;
import com.toadzip.backend.ingest.mapping.dto.ComplexMergeRequest;
import com.toadzip.backend.ingest.mapping.dto.ComplexMergeResponse;
import com.toadzip.backend.ingest.mapping.repository.MyHomeComplexLinkRepository;
import com.toadzip.backend.ingest.mapping.repository.MyHomeComplexMergeRepository;
import com.toadzip.backend.ingest.mapping.repository.MyHomeComplexMergeStore;
import com.toadzip.backend.ingest.mapping.service.ComplexMergePreparation.PreparedMerge;
import com.toadzip.backend.ingest.pipeline.service.IngestWriteOwnershipGuard;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** 확인한 마이홈 단지들을 대표 단지 하나로 합치고, 이후 바뀐 것이 없을 때만 통합 전으로 되돌린다. */
@Service
public class MyHomeComplexMergeService {

    private final HousingComplexRepository complexes;
    private final MyHomeComplexLinkRepository links;
    private final MyHomeComplexMergeRepository merges;
    private final ComplexMergePreparation preparation;
    private final MyHomeComplexMergeStore store;
    private final IngestWriteOwnershipGuard ownershipGuard;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public MyHomeComplexMergeService(
            HousingComplexRepository complexes, MyHomeComplexLinkRepository links,
            MyHomeComplexMergeRepository merges, ComplexMergePreparation preparation,
            MyHomeComplexMergeStore store, IngestWriteOwnershipGuard ownershipGuard,
            PlatformTransactionManager transactionManager, Clock clock
    ) {
        this.complexes = complexes;
        this.links = links;
        this.merges = merges;
        this.preparation = preparation;
        this.store = store;
        this.ownershipGuard = ownershipGuard;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ComplexMergePreviewResponse preview(ComplexMergePreviewRequest request) {
        return preparation.prepare(request, false).response();
    }

    public ComplexMergeResponse merge(ComplexMergeRequest request, String actor) {
        return transaction.execute(status -> mergeLocked(request, actor));
    }

    public ComplexMergeResponse revert(UUID id, String actor) {
        return transaction.execute(status -> revertLocked(id, actor));
    }

    @Transactional(readOnly = true)
    public ComplexMergeResponse get(UUID id) {
        return response(findMerge(id));
    }

    private ComplexMergeResponse mergeLocked(ComplexMergeRequest request, String actor) {
        ownershipGuard.verifyWrite();
        require(request.confirmedSameComplex(), "동일 실제 단지와 공급 범위를 명시적으로 확인해야 합니다.");
        MyHomeComplexMerge previous = merges.findById(request.operationId()).orElse(null);
        if (previous != null) {
            require(previous.getPreviewHash().equals(request.expectedHash())
                    && previous.getComplexIds().equals(request.complexIds().stream().sorted().toList())
                    && previous.getReason().equals(request.reason()), "같은 작업 ID에 다른 통합 요청을 사용할 수 없습니다.");
            return response(previous);
        }
        PreparedMerge prepared = preparation.prepare(
                new ComplexMergePreviewRequest(request.complexIds(), request.lhSourceId()), true);
        ComplexMergePreviewResponse preview = prepared.response();
        require(preview.previewHash().equals(request.expectedHash()),
                "미리보기 이후 원천 또는 제품 데이터가 바뀌었습니다. 다시 확인해야 합니다.");
        MyHomeComplexMerge merge = MyHomeComplexMerge.verified(
                request.operationId(), preview.complexIds(), preview.adoptedHouseholdCount(),
                prepared.before(), prepared.evidence(), preview.previewHash(), request.reason(), actor, clock.instant()
        );
        merges.saveAndFlush(merge);
        HousingComplex representative = prepared.complexes().getFirst();
        MyHomeComplexMerge.manualValues(prepared.complexes()).adoptInto(representative, preview.adoptedHouseholdCount());
        for (int index = 0; index < prepared.links().size(); index++) {
            prepared.links().get(index).verifyAndConnect(
                    representative, merge.getId(), preview.sources().get(index).householdCount());
        }
        links.flush();
        store.transferAndRemove(representative.getId(), preview.complexIds().subList(1, preview.complexIds().size()),
                merge.getId());
        merge.complete(store.state(preview.complexIds()));
        return response(merges.save(merge));
    }

    private ComplexMergeResponse revertLocked(UUID id, String actor) {
        ownershipGuard.verifyWrite();
        MyHomeComplexMerge merge = findMerge(id);
        if (merge.getRevertedAt() != null) {
            return response(merge);
        }
        require(merge.getSnapshotVersion() == 1, "현재 버전으로 복구할 수 없는 이력입니다.");
        complexes.findByIdForUpdate(merge.getRepresentativeId())
                .orElseThrow(() -> conflict("대표 단지가 사라져 복구할 수 없습니다."));
        store.lockReferences(merge.getComplexIds());
        require(store.sameState(store.state(merge.getComplexIds()), merge.getAfterState()),
                "통합 이후 제품 데이터 또는 참조가 변경되었습니다. 후속 수정을 보존하기 위해 복구를 보류합니다.");
        store.restore(merge.getBeforeState(), id);
        merge.revert(actor, clock.instant());
        return response(merges.save(merge));
    }

    private MyHomeComplexMerge findMerge(UUID id) {
        return merges.findById(id).orElseThrow(() -> conflict("통합 이력을 찾을 수 없습니다."));
    }

    private ComplexMergeResponse response(MyHomeComplexMerge merge) {
        return new ComplexMergeResponse(merge.getId(), merge.getRepresentativeId(), merge.getComplexIds(),
                merge.getAdoptedHouseholdCount(), merge.getReason(), merge.getVerifiedBy(), merge.getMergedAt(),
                merge.getRevertedBy(), merge.getRevertedAt(), merge.getEvidence());
    }
}
