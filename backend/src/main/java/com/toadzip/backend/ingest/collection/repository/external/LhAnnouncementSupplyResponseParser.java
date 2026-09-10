package com.toadzip.backend.ingest.collection.repository.external;

import com.toadzip.backend.ingest.collection.domain.LhAnnouncementSupplySource;
import com.toadzip.backend.ingest.collection.domain.LhAnnouncementSupplySourceSnapshot;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
public class LhAnnouncementSupplyResponseParser {

    private static final String DATASET_KEY = "dsList01";

    public List<LhAnnouncementSupplySource> parse(String panId, JsonNode root) {
        requireDataset(root);
        List<JsonNode> rows = ExternalResponseRows.find(root, DATASET_KEY);
        List<LhAnnouncementSupplySource> sources = new ArrayList<>();
        for (int sourceOrder = 0; sourceOrder < rows.size(); sourceOrder++) {
            sources.add(new LhAnnouncementSupplySource(
                        sourceOrder,
                        panId,
                        sourceSnapshotOf(rows.get(sourceOrder))
            ));
        }
        return sources;
    }

    private void requireDataset(JsonNode root) {
        if (!containsDataset(root)) {
            throw new ExternalDataRequestException("LH 공고 공급 응답에 예상 dataset이 없습니다.");
        }
    }

    private boolean containsDataset(JsonNode root) {
        return ExternalResponseRows.contains(root, DATASET_KEY);
    }

    private LhAnnouncementSupplySourceSnapshot sourceSnapshotOf(JsonNode row) {
        return new LhAnnouncementSupplySourceSnapshot(
                text(row, "SBD_LGO_NM"),
                text(row, "HTY_NNA"),
                text(row, "DDO_AR"),
                text(row, "SPL_AR"),
                text(row, "HSH_CNT"),
                text(row, "NOW_HSH_CNT"),
                text(row, "LS_GMY"),
                text(row, "RFE")
        );
    }

    private String text(JsonNode row, String field) {
        return row.path(field).asString(null);
    }

}
