package com.toadzip.backend.ingest.collection.fixture;

import com.toadzip.backend.ingest.collection.lh.announcementcatalog.domain.LhAnnouncementCatalogSnapshot;
import com.toadzip.backend.ingest.collection.lh.announcementcatalog.domain.LhAnnouncementCatalogSourceView;
import com.toadzip.backend.ingest.collection.lh.detail.domain.LhAnnouncementDetailSourceSnapshot;
import com.toadzip.backend.ingest.collection.lh.detail.domain.LhAnnouncementDetailSourceView;
import com.toadzip.backend.ingest.collection.lh.leasecatalog.domain.LhCatalogSourceSnapshot;
import com.toadzip.backend.ingest.collection.lh.leasecatalog.domain.LhLeaseCatalogSourceView;
import com.toadzip.backend.ingest.collection.lh.supply.domain.LhAnnouncementSupplySourceSnapshot;
import com.toadzip.backend.ingest.collection.lh.supply.domain.LhAnnouncementSupplySourceView;
import java.time.Instant;

/** 테스트가 저장 전 LH 원천을 운영과 같은 read 경로로 만든다. */
public final class LhSourceViews {

    private LhSourceViews() {
    }

    public static LhAnnouncementSupplySourceView supply(
            int order, String panId, LhAnnouncementSupplySourceSnapshot snapshot
    ) {
        return LhAnnouncementSupplySourceView.read(null, order, panId, null, null, snapshot);
    }

    public static LhAnnouncementDetailSourceView detail(
            int order, String panId, LhAnnouncementDetailSourceSnapshot snapshot
    ) {
        return LhAnnouncementDetailSourceView.read(null, order, panId, null, null, snapshot);
    }

    /** 같은 원천을 다른 조회 조건 해시로 읽은 것처럼 만든다. */
    public static LhAnnouncementSupplySourceView requested(LhAnnouncementSupplySourceView source, String requestHash) {
        return LhAnnouncementSupplySourceView.read(source.getId(), source.getSourceOrder(), source.getPanId(),
                requestHash, source.getCollectedAt(), new LhAnnouncementSupplySourceSnapshot(
                        source.getComplexLabel(), source.getTypeName(), source.getExclusiveArea(),
                        source.getSupplyArea(), source.getTotalUnitCount(), source.getSuppliedUnitCount(),
                        source.getDepositText(), source.getMonthlyRentText()));
    }

    /** 같은 원천을 다른 조회 조건 해시로 읽은 것처럼 만든다. */
    public static LhAnnouncementDetailSourceView requested(LhAnnouncementDetailSourceView source, String requestHash) {
        return LhAnnouncementDetailSourceView.read(source.getId(), source.getSourceOrder(), source.getPanId(),
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

    /** 목록에서 처음 본 공고처럼 변경 시각과 수집 시각이 같은 원천을 만든다. */
    public static LhAnnouncementCatalogSourceView announcementCatalog(
            Instant observedAt, LhAnnouncementCatalogSnapshot snapshot
    ) {
        return LhAnnouncementCatalogSourceView.read(null, observedAt, observedAt, true, snapshot);
    }

    public static LhLeaseCatalogSourceView catalog(int order, Instant collectedAt, LhCatalogSourceSnapshot snapshot) {
        return LhLeaseCatalogSourceView.read(null, order, collectedAt, snapshot);
    }
}
