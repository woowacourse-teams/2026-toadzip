package com.toadzip.backend.ingest.collection.fixture;

import com.toadzip.backend.ingest.lh.collection.announcementcatalog.domain.LhAnnouncementCatalogSnapshot;
import com.toadzip.backend.ingest.lh.collection.announcementcatalog.domain.LhAnnouncementCatalogSourceView;
import com.toadzip.backend.ingest.lh.collection.detail.domain.LhAnnouncementDetailSourceSnapshot;
import com.toadzip.backend.ingest.lh.collection.detail.domain.LhAnnouncementDetailSourceView;
import com.toadzip.backend.ingest.lh.collection.leasecatalog.domain.LhCatalogSourceSnapshot;
import com.toadzip.backend.ingest.lh.collection.leasecatalog.domain.LhLeaseCatalogSourceView;
import com.toadzip.backend.ingest.lh.collection.supply.domain.LhAnnouncementSupplySourceSnapshot;
import com.toadzip.backend.ingest.lh.collection.supply.domain.LhAnnouncementSupplySourceView;
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
                requestHash, source.getCollectedAt(), snapshotOf(source, source.getComplexName()));
    }

    /** 같은 상세 원천이 단지명만 바뀌어 다시 수집된 경우. */
    public static LhAnnouncementDetailSourceView complexRenamed(
            LhAnnouncementDetailSourceView source, String complexName
    ) {
        return LhAnnouncementDetailSourceView.read(source.getId(), source.getSourceOrder(), source.getPanId(),
                source.getRequestHash(), source.getCollectedAt(), snapshotOf(source, complexName));
    }

    private static LhAnnouncementDetailSourceSnapshot snapshotOf(
            LhAnnouncementDetailSourceView source, String complexName
    ) {
        return new LhAnnouncementDetailSourceSnapshot(
                source.getDatasetType(), complexName, source.getAddress(),
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
                source.getEtcContents());
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
