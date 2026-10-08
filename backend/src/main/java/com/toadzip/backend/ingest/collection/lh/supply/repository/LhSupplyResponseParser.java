package com.toadzip.backend.ingest.collection.lh.supply.repository;

import com.toadzip.backend.ingest.collection.lh.repository.LhResponseDatasetReader;
import com.toadzip.backend.ingest.collection.lh.supply.domain.LhAnnouncementSupplySourceSnapshot;
import com.toadzip.backend.ingest.collection.repository.external.ExternalDataRequestException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
public class LhSupplyResponseParser {

    private static final String STANDARD_DATASET_KEY = "dsList01";
    private static final String PUBLIC_RENTAL_DATASET_KEY = "dsList02";

    public List<LhAnnouncementSupplySourceSnapshot> parse(String supplyInfoTypeCode, JsonNode root) {
        String datasetKey = datasetKeyOf(supplyInfoTypeCode);
        requireDataset(root, datasetKey);
        List<JsonNode> rows = new ArrayList<>();
        LhResponseDatasetReader.require(root, datasetKey).forEach(rows::add);
        rejectMismatchedNonEmptyDataset(root, datasetKey, rows);
        List<LhAnnouncementSupplySourceSnapshot> sources = new ArrayList<>();
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            sources.add(sourceSnapshotOf(rows.get(rowIndex), supplyInfoTypeCode));
        }
        return List.copyOf(sources);
    }

    private String datasetKeyOf(String supplyInfoTypeCode) {
        if ("060".equals(supplyInfoTypeCode)) {
            return PUBLIC_RENTAL_DATASET_KEY;
        }
        return STANDARD_DATASET_KEY;
    }

    private void requireDataset(JsonNode root, String datasetKey) {
        if (!contains(root, datasetKey)) {
            throw new ExternalDataRequestException("LH 공고 공급 응답에 예상 dataset이 없습니다.");
        }
    }

    private void rejectMismatchedNonEmptyDataset(
            JsonNode root,
            String datasetKey,
            List<JsonNode> rows
    ) {
        if (!rows.isEmpty()) {
            return;
        }
        String otherDatasetKey = STANDARD_DATASET_KEY;
        if (STANDARD_DATASET_KEY.equals(datasetKey)) {
            otherDatasetKey = PUBLIC_RENTAL_DATASET_KEY;
        }
        if (contains(root, otherDatasetKey) && !LhResponseDatasetReader.require(root, otherDatasetKey).isEmpty()) {
            throw new ExternalDataRequestException("LH 공고 공급 응답의 유형별 dataset이 일치하지 않습니다.");
        }
    }

    private LhAnnouncementSupplySourceSnapshot sourceSnapshotOf(JsonNode row, String supplyInfoTypeCode) {
        if ("060".equals(supplyInfoTypeCode)) {
            String complexLabel = text(row, "BZDT_NM");
            String typeName = text(row, "HTY_NM");
            requireIdentity(complexLabel, typeName);
            return new LhAnnouncementSupplySourceSnapshot(
                    complexLabel,
                    typeName,
                    text(row, "RSDN_DDO_AR"),
                    text(row, "SPL_AR"),
                    text(row, "TOT_HSH_CNT"),
                    text(row, "SIL_HSH_CNT"),
                    text(row, "LS_GMY"),
                    text(row, "MM_RFE")
            );
        }
        String complexLabel = text(row, "SBD_LGO_NM");
        String typeName = text(row, "HTY_NNA");
        requireIdentity(complexLabel, typeName);
        return new LhAnnouncementSupplySourceSnapshot(
                complexLabel,
                typeName,
                text(row, "DDO_AR"),
                text(row, "SPL_AR"),
                text(row, "HSH_CNT"),
                text(row, "NOW_HSH_CNT"),
                text(row, "LS_GMY"),
                text(row, "RFE")
        );
    }

    private void requireIdentity(String complexLabel, String typeName) {
        if (complexLabel == null || complexLabel.isBlank()
                || typeName == null || typeName.isBlank()) {
            throw new ExternalDataRequestException("LH 공고 공급행의 단지명 또는 주택형명이 없습니다.");
        }
    }

    private String text(JsonNode row, String field) {
        return LhResponseDatasetReader.text(row, field);
    }

    private boolean contains(JsonNode root, String key) {
        if (!root.isArray()) {
            throw new ExternalDataRequestException("LH 공급 응답 구조가 올바르지 않습니다.");
        }
        for (JsonNode dataset : root) {
            if (dataset.has(key)) {
                return true;
            }
        }
        return false;
    }
}
