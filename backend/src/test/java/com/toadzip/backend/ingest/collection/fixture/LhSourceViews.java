package com.toadzip.backend.ingest.collection.fixture;

import com.toadzip.backend.ingest.collection.lh.detail.domain.LhAnnouncementDetailSource;
import com.toadzip.backend.ingest.collection.lh.detail.domain.LhAnnouncementDetailSourceSnapshot;
import com.toadzip.backend.ingest.collection.lh.leasecatalog.domain.LhCatalogSource;
import com.toadzip.backend.ingest.collection.lh.leasecatalog.domain.LhCatalogSourceSnapshot;
import com.toadzip.backend.ingest.collection.lh.supply.domain.LhAnnouncementSupplySource;
import com.toadzip.backend.ingest.collection.lh.supply.domain.LhAnnouncementSupplySourceSnapshot;
import java.time.Instant;

/** 테스트가 저장 전 LH 원천을 운영과 같은 read 경로로 만든다. */
public final class LhSourceViews {

    private LhSourceViews() {
    }

    public static LhAnnouncementSupplySource supply(
            int order, String panId, LhAnnouncementSupplySourceSnapshot snapshot
    ) {
        return LhAnnouncementSupplySource.read(null, order, panId, null, null, snapshot);
    }

    public static LhAnnouncementDetailSource detail(
            int order, String panId, LhAnnouncementDetailSourceSnapshot snapshot
    ) {
        return LhAnnouncementDetailSource.read(null, order, panId, null, null, snapshot);
    }

    /** 같은 원천을 다른 조회 조건 해시로 읽은 것처럼 만든다. */
    public static LhAnnouncementSupplySource requested(LhAnnouncementSupplySource source, String requestHash) {
        return LhAnnouncementSupplySource.read(source.getId(), source.getSourceOrder(), source.getPanId(),
                requestHash, source.getCollectedAt(), new LhAnnouncementSupplySourceSnapshot(
                        source.getComplexLabel(), source.getTypeName(), source.getExclusiveArea(),
                        source.getSupplyArea(), source.getTotalUnitCount(), source.getSuppliedUnitCount(),
                        source.getDepositText(), source.getMonthlyRentText()));
    }

    /** 같은 원천을 다른 조회 조건 해시로 읽은 것처럼 만든다. */
    public static LhAnnouncementDetailSource requested(LhAnnouncementDetailSource source, String requestHash) {
        return LhAnnouncementDetailSource.read(source.getId(), source.getSourceOrder(), source.getPanId(),
                requestHash, source.getCollectedAt(), new LhAnnouncementDetailSourceSnapshot(
                        source.getDatasetType(), source.getComplexName(), source.getAddress(),
                        source.getDetailAddress(), source.getTotalUnitCount(), source.getHeatingDescription(),
                        source.getExclusiveAreaRange(), source.getExpectedMoveInYearMonth(),
                        source.getGuidanceText(), source.getApplicationPeriod(), source.getApplicationBeginDate(),
                        source.getApplicationEndDate(), source.getWinnerAnnouncementDate(),
                        source.getDocumentTargetAnnouncementDate(), source.getDocumentSubmissionBeginDate(),
                        source.getDocumentSubmissionEndDate(), source.getContractBeginDate(),
                        source.getContractEndDate(), source.getReceptionAddress(),
                        source.getReceptionDetailAddress(), source.getOperationBegin(), source.getOperationEnd(),
                        source.getPhone(), source.getReceptionGuidance(), source.getKind(), source.getName(),
                        source.getUrl(), source.getAttachmentComplexName(), source.getCorrectionReason(),
                        source.getEtcContents()));
    }

    public static LhCatalogSource catalog(int order, Instant collectedAt, LhCatalogSourceSnapshot snapshot) {
        return LhCatalogSource.read(null, order, collectedAt, snapshot);
    }
}
