package com.toadzip.backend.ingest.collection.repository.external;

import com.toadzip.backend.ingest.collection.domain.LhCatalogSourceSnapshot;
import com.toadzip.backend.ingest.collection.dto.ExternalDataPage;
import com.toadzip.backend.ingest.collection.dto.ExternalDataResponse;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
public class LhLeaseCatalogResponseParser {

    private static final String LIST_KEY = "dsList";

    public ExternalDataPage<LhCatalogSourceSnapshot> parse(ExternalDataResponse response) {
        requireDataset(response.body());
        List<LhCatalogSourceSnapshot> snapshots = ExternalResponseRows.find(response.body(), LIST_KEY)
                .stream()
                .map(this::sourceSnapshotOf)
                .toList();
        return new ExternalDataPage<>(snapshots, -1);
    }

    private void requireDataset(JsonNode root) {
        if (!ExternalResponseRows.contains(root, LIST_KEY)) {
            throw new ExternalDataRequestException("LH 임대 카탈로그 응답에 예상 dataset이 없습니다.");
        }
    }

    private LhCatalogSourceSnapshot sourceSnapshotOf(JsonNode row) {
        return new LhCatalogSourceSnapshot(
                text(row, "ARA_NM"),
                text(row, "AIS_TP_CD_NM"),
                text(row, "SBD_LGO_NM"),
                text(row, "SUM_HSH_CNT"),
                text(row, "DDO_AR"),
                text(row, "HSH_CNT"),
                text(row, "LS_GMY"),
                text(row, "RFE")
        );
    }

    private String text(JsonNode row, String field) {
        return row.path(field).asString(null);
    }

}
