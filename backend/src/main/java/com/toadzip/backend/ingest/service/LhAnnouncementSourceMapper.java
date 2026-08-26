package com.toadzip.backend.ingest.service;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

import com.toadzip.backend.ingest.domain.LhAnnouncementDetailSource;
import com.toadzip.backend.ingest.domain.LhAnnouncementSupplySource;
import com.toadzip.backend.ingest.dto.LhAnnouncementSupplySourceItem;
import com.toadzip.backend.ingest.repository.external.DataGoKrOpenApiClient;
import com.toadzip.backend.ingest.repository.external.ExternalDataRequestException;
import tools.jackson.databind.JsonNode;

@Component
public class LhAnnouncementSourceMapper {

    private static final List<String> DETAIL_DATASET_KEYS = List.of(
            "dsEtcInfo",
            "dsSbd",
            "dsSplScdl",
            "dsCtrtPlc",
            "dsAhflInfo",
            "dsSbdAhfl"
    );

    private static final String SUPPLY_DATASET_KEY = "dsList01";

    public List<LhAnnouncementDetailSource> details(String panId, JsonNode root) {
        requireAnyDataset(root, DETAIL_DATASET_KEYS, "LH 공고 상세");
        List<LhAnnouncementDetailSource> sources = new ArrayList<>();
        addEtcInfo(sources, panId, root);
        addComplexes(sources, panId, root);
        addSchedules(sources, panId, root);
        addReceptions(sources, panId, root);
        addAnnouncementFiles(sources, panId, root);
        addComplexImages(sources, panId, root);
        return sources;
    }

    public List<LhAnnouncementSupplySource> supplies(String panId, JsonNode root) {
        requireAnyDataset(root, List.of(SUPPLY_DATASET_KEY), "LH 공고 공급");
        List<JsonNode> rows = DataGoKrOpenApiClient.findRows(root, SUPPLY_DATASET_KEY);
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

    private void requireAnyDataset(JsonNode root, List<String> datasetKeys, String sourceName) {
        boolean containsDataset = datasetKeys.stream().anyMatch(key -> containsDataset(root, key));
        if (!containsDataset) {
            throw new ExternalDataRequestException(sourceName + " 응답에 예상 dataset이 없습니다.");
        }
    }

    private boolean containsDataset(JsonNode root, String datasetKey) {
        if (!root.isArray()) {
            return root.has(datasetKey);
        }
        for (JsonNode element : root) {
            if (element.has(datasetKey)) {
                return true;
            }
        }
        return false;
    }

    private void addEtcInfo(List<LhAnnouncementDetailSource> sources, String panId, JsonNode root) {
        for (JsonNode row : DataGoKrOpenApiClient.findRows(root, "dsEtcInfo")) {
            sources.add(detail(sources.size(), panId, "ETC_INFO")
                    .correctionReason(text(row, "CRC_RSN"))
                    .etcContents(text(row, "ETC_CTS"))
                    .build());
        }
    }

    private void addComplexes(List<LhAnnouncementDetailSource> sources, String panId, JsonNode root) {
        for (JsonNode row : DataGoKrOpenApiClient.findRows(root, "dsSbd")) {
            sources.add(detail(sources.size(), panId, "COMPLEX")
                    .complexName(text(row, "LCC_NT_NM"))
                    .address(text(row, "LGDN_ADR"))
                    .detailAddress(text(row, "LGDN_DTL_ADR"))
                    .totalUnitCount(text(row, "HSH_CNT"))
                    .heatingDescription(text(row, "HTN_FMLA_DESC"))
                    .exclusiveAreaRange(text(row, "DDO_AR"))
                    .expectedMoveInYearMonth(text(row, "MVIN_XPC_YM"))
                    .guidanceText(text(row, "SPL_INF_GUD_FCTS"))
                    .build());
        }
    }

    private void addSchedules(List<LhAnnouncementDetailSource> sources, String panId, JsonNode root) {
        for (JsonNode row : DataGoKrOpenApiClient.findRows(root, "dsSplScdl")) {
            sources.add(detail(sources.size(), panId, "SCHEDULE")
                    .complexName(text(row, "SBD_LGO_NM"))
                    .applicationPeriod(text(row, "ACP_DTTM"))
                    .documentTargetAnnouncementDate(text(row, "PPR_SBM_OPE_ANC_DT"))
                    .documentSubmissionBeginDate(text(row, "PPR_ACP_ST_DT"))
                    .documentSubmissionEndDate(text(row, "PPR_ACP_CLSG_DT"))
                    .contractBeginDate(text(row, "CTRT_ST_DT"))
                    .contractEndDate(text(row, "CTRT_ED_DT"))
                    .build());
        }
    }

    private void addReceptions(List<LhAnnouncementDetailSource> sources, String panId, JsonNode root) {
        for (JsonNode row : DataGoKrOpenApiClient.findRows(root, "dsCtrtPlc")) {
            sources.add(detail(sources.size(), panId, "RECEPTION")
                    .receptionAddress(text(row, "CTRT_PLC_ADR"))
                    .receptionDetailAddress(text(row, "CTRT_PLC_DTL_ADR"))
                    .operationBegin(text(row, "TSK_ST_DTTM"))
                    .operationEnd(text(row, "TSK_ED_DTTM"))
                    .phone(text(row, "SIL_OFC_TLNO"))
                    .receptionGuidance(text(row, "SIL_OFC_GUD_FCTS"))
                    .build());
        }
    }

    private void addAnnouncementFiles(List<LhAnnouncementDetailSource> sources, String panId, JsonNode root) {
        for (JsonNode row : DataGoKrOpenApiClient.findRows(root, "dsAhflInfo")) {
            sources.add(detail(sources.size(), panId, "ANNOUNCEMENT_FILE")
                    .kind(text(row, "SL_PAN_AHFL_DS_CD_NM"))
                    .name(text(row, "CMN_AHFL_NM"))
                    .url(text(row, "AHFL_URL"))
                    .build());
        }
    }

    private void addComplexImages(List<LhAnnouncementDetailSource> sources, String panId, JsonNode root) {
        for (JsonNode row : DataGoKrOpenApiClient.findRows(root, "dsSbdAhfl")) {
            sources.add(detail(sources.size(), panId, "COMPLEX_IMAGE")
                    .kind(text(row, "LS_SPL_INF_UPL_FL_DS_CD_NM"))
                    .name(text(row, "CMN_AHFL_NM"))
                    .url(text(row, "AHFL_URL"))
                    .attachmentComplexName(text(row, "LCC_NT_NM"))
                    .build());
        }
    }

    private DetailBuilder detail(int sourceOrder, String panId, String datasetType) {
        return new DetailBuilder(sourceOrder, panId, datasetType);
    }

    private String text(JsonNode row, String field) {
        return row.path(field).asString(null);
    }

    private static final class DetailBuilder {

        private final int sourceOrder;
        private final String panId;
        private final String datasetType;
        private String complexName;
        private String address;
        private String detailAddress;
        private String totalUnitCount;
        private String heatingDescription;
        private String exclusiveAreaRange;
        private String expectedMoveInYearMonth;
        private String guidanceText;
        private String applicationPeriod;
        private String documentTargetAnnouncementDate;
        private String documentSubmissionBeginDate;
        private String documentSubmissionEndDate;
        private String contractBeginDate;
        private String contractEndDate;
        private String receptionAddress;
        private String receptionDetailAddress;
        private String operationBegin;
        private String operationEnd;
        private String phone;
        private String receptionGuidance;
        private String kind;
        private String name;
        private String url;
        private String attachmentComplexName;
        private String correctionReason;
        private String etcContents;

        private DetailBuilder(int sourceOrder, String panId, String datasetType) {
            this.sourceOrder = sourceOrder;
            this.panId = panId;
            this.datasetType = datasetType;
        }

        private DetailBuilder complexName(String value) { complexName = value; return this; }
        private DetailBuilder address(String value) { address = value; return this; }
        private DetailBuilder detailAddress(String value) { detailAddress = value; return this; }
        private DetailBuilder totalUnitCount(String value) { totalUnitCount = value; return this; }
        private DetailBuilder heatingDescription(String value) { heatingDescription = value; return this; }
        private DetailBuilder exclusiveAreaRange(String value) { exclusiveAreaRange = value; return this; }
        private DetailBuilder expectedMoveInYearMonth(String value) { expectedMoveInYearMonth = value; return this; }
        private DetailBuilder guidanceText(String value) { guidanceText = value; return this; }
        private DetailBuilder applicationPeriod(String value) { applicationPeriod = value; return this; }
        private DetailBuilder documentTargetAnnouncementDate(String value) {
            documentTargetAnnouncementDate = value;
            return this;
        }

        private DetailBuilder documentSubmissionBeginDate(String value) {
            documentSubmissionBeginDate = value;
            return this;
        }

        private DetailBuilder documentSubmissionEndDate(String value) {
            documentSubmissionEndDate = value;
            return this;
        }
        private DetailBuilder contractBeginDate(String value) { contractBeginDate = value; return this; }
        private DetailBuilder contractEndDate(String value) { contractEndDate = value; return this; }
        private DetailBuilder receptionAddress(String value) { receptionAddress = value; return this; }
        private DetailBuilder receptionDetailAddress(String value) { receptionDetailAddress = value; return this; }
        private DetailBuilder operationBegin(String value) { operationBegin = value; return this; }
        private DetailBuilder operationEnd(String value) { operationEnd = value; return this; }
        private DetailBuilder phone(String value) { phone = value; return this; }
        private DetailBuilder receptionGuidance(String value) { receptionGuidance = value; return this; }
        private DetailBuilder kind(String value) { kind = value; return this; }
        private DetailBuilder name(String value) { name = value; return this; }
        private DetailBuilder url(String value) { url = value; return this; }
        private DetailBuilder attachmentComplexName(String value) { attachmentComplexName = value; return this; }
        private DetailBuilder correctionReason(String value) { correctionReason = value; return this; }
        private DetailBuilder etcContents(String value) { etcContents = value; return this; }

        private LhAnnouncementDetailSource build() {
            return new LhAnnouncementDetailSource(
                    sourceOrder, panId, datasetType, complexName, address, detailAddress, totalUnitCount,
                    heatingDescription, exclusiveAreaRange, expectedMoveInYearMonth, guidanceText,
                    applicationPeriod, documentTargetAnnouncementDate, documentSubmissionBeginDate,
                    documentSubmissionEndDate, contractBeginDate, contractEndDate, receptionAddress,
                    receptionDetailAddress, operationBegin, operationEnd, phone, receptionGuidance, kind,
                    name, url, attachmentComplexName, correctionReason, etcContents
            );
        }
    }
}
