package com.toadzip.backend.ingest.collection.repository.external;

import com.toadzip.backend.ingest.collection.domain.LhAnnouncementSupplySource;
import com.toadzip.backend.ingest.collection.dto.LhAnnouncementSupplySourceItem;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
public class LhAnnouncementSupplyResponseParser {

    private static final String DATASET_KEY = "dsList01";

    public List<LhAnnouncementSupplySource> parse(String panId, JsonNode root) {
        requireDataset(root);
        List<JsonNode> rows = DataGoKrOpenApiClient.findRows(root, DATASET_KEY);
        List<LhAnnouncementSupplySource> sources = new ArrayList<>();
        for (int sourceOrder = 0; sourceOrder < rows.size(); sourceOrder++) {
            sources.add(new LhAnnouncementSupplySource(
                        sourceOrder,
                        panId,
                        LhAnnouncementSupplySourceItem.from(rows.get(sourceOrder)).toSourceData()
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
        if (!root.isArray()) {
            return root.has(DATASET_KEY);
        }
        for (JsonNode element : root) {
            if (element.has(DATASET_KEY)) {
                return true;
            }
        }
        return false;
    }
}
