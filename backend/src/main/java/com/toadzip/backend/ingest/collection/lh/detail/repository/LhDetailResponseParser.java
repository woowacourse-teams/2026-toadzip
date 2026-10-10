package com.toadzip.backend.ingest.collection.lh.detail.repository;

import com.toadzip.backend.ingest.collection.lh.detail.domain.LhAnnouncementDetailSourceSnapshot;
import com.toadzip.backend.ingest.collection.lh.repository.LhResponseDatasetReader;
import com.toadzip.backend.ingest.collection.repository.external.ExternalDataRequestException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
public class LhDetailResponseParser {

    private static final List<String> DETAIL_DATASET_KEYS = List.of(
            "dsEtcInfo",
            "dsSbd",
            "dsSplScdl",
            "dsCtrtPlc",
            "dsAhflInfo",
            "dsSbdAhfl"
    );

    public List<LhAnnouncementDetailSourceSnapshot> parse(JsonNode root) {
        requireAnyDataset(root, DETAIL_DATASET_KEYS, "LH 공고 상세");
        List<LhAnnouncementDetailSourceSnapshot> sources = new ArrayList<>();
        addEtcInfo(sources, root);
        addComplexes(sources, root);
        addSchedules(sources, root);
        addReceptions(sources, root);
        addAnnouncementFiles(sources, root);
        addComplexImages(sources, root);
        if (sources.stream().anyMatch(source -> !hasContent(source))) {
            throw new ExternalDataRequestException("LH 공고 상세 응답에 내용 없는 행이 있습니다.");
        }
        return List.copyOf(sources);
    }

    private void requireAnyDataset(JsonNode root, List<String> datasetKeys, String sourceName) {
        boolean containsDataset = datasetKeys.stream().anyMatch(key -> containsDataset(root, key));
        if (!containsDataset) {
            throw new ExternalDataRequestException(sourceName + " 응답에 예상 dataset이 없습니다.");
        }
    }

    private boolean containsDataset(JsonNode root, String datasetKey) {
        if (!root.isArray()) {
            throw new ExternalDataRequestException("LH 상세 응답 구조가 올바르지 않습니다.");
        }
        for (JsonNode entry : root) {
            if (entry.has(datasetKey)) {
                return true;
            }
        }
        return false;
    }

    private void addEtcInfo(
            List<LhAnnouncementDetailSourceSnapshot> sources,
            JsonNode root
    ) {
        for (JsonNode row : rows(root, "dsEtcInfo")) {
            LhAnnouncementDetailSourceSnapshot source = detail("ETC_INFO")
                    .correctionReason(text(row, "CRC_RSN"))
                    .etcContents(etcContents(row))
                    .build();
            if (!hasContent(source) && isKnownEmptyEtcInfo(row)) {
                continue;
            }
            sources.add(source);
        }
    }

    private String etcContents(JsonNode row) {
        return Stream.of(text(row, "ETC_CTS"), text(row, "ETC_FCTS"), text(row, "PAN_DTL_CTS"))
                .filter(value -> value != null && !value.isBlank())
                .collect(Collectors.joining("\n"));
    }

    private boolean isKnownEmptyEtcInfo(JsonNode row) {
        return isKnownEmptyRow(row, List.of("CRC_RSN", "ETC_CTS"))
                || isKnownEmptyRow(row, List.of("ETC_FCTS", "PAN_DTL_CTS"));
    }

    private boolean isKnownEmptyRow(JsonNode row, List<String> fields) {
        return row.size() == fields.size()
                && fields.stream().allMatch(field -> isExplicitlyEmptyText(row.get(field)));
    }

    private boolean isExplicitlyEmptyText(JsonNode value) {
        if (value == null) {
            return false;
        }
        return value.isNull() || (value.isTextual() && value.asString().isBlank());
    }

    private void addComplexes(
            List<LhAnnouncementDetailSourceSnapshot> sources,
            JsonNode root
    ) {
        for (JsonNode row : rows(root, "dsSbd")) {
            sources.add(detail("COMPLEX")
                    .complexName(text(row, "LCC_NT_NM", "BZDT_NM"))
                    .address(text(row, "LGDN_ADR", "LCT_ARA_ADR"))
                    .detailAddress(text(row, "LGDN_DTL_ADR", "LCT_ARA_DTL_ADR"))
                    .totalUnitCount(text(row, "HSH_CNT", "SUM_TOT_HSH_CNT"))
                    .heatingDescription(text(row, "HTN_FMLA_DESC", "HTN_FMLA_DS_CD_NM"))
                    .exclusiveAreaRange(text(row, "DDO_AR", "MIN_MAX_RSDN_DDO_AR"))
                    .expectedMoveInYearMonth(text(row, "MVIN_XPC_YM"))
                    .guidanceText(text(row, "SPL_INF_GUD_FCTS"))
                    .build());
        }
    }

    private void addSchedules(
            List<LhAnnouncementDetailSourceSnapshot> sources,
            JsonNode root
    ) {
        for (JsonNode row : rows(root, "dsSplScdl")) {
            DetailBuilder builder = detail("SCHEDULE")
                    .complexName(text(row, "SBD_LGO_NM"))
                    .applicationPeriod(text(row, "ACP_DTTM"))
                    .documentTargetAnnouncementDate(text(row, "PPR_SBM_OPE_ANC_DT"))
                    .documentSubmissionBeginDate(text(row, "PPR_ACP_ST_DT", "PZWR_PPR_SBM_ST_DT"))
                    .documentSubmissionEndDate(text(row, "PPR_ACP_CLSG_DT", "PZWR_PPR_SBM_ED_DT"))
                    .contractBeginDate(text(row, "CTRT_ST_DT"))
                    .contractEndDate(text(row, "CTRT_ED_DT"));
            builder.applicationBeginDate = text(row, "SBSC_ACP_ST_DT");
            builder.applicationEndDate = text(row, "SBSC_ACP_CLSG_DT");
            builder.winnerAnnouncementDate = text(row, "PZWR_ANC_DT");
            LhAnnouncementDetailSourceSnapshot source = builder.build();
            sources.add(source);
        }
    }

    private void addReceptions(
            List<LhAnnouncementDetailSourceSnapshot> sources,
            JsonNode root
    ) {
        for (JsonNode row : rows(root, "dsCtrtPlc")) {
            if (isKnownEmptyRow(row, List.of("CTRT_PLC_ADR", "CTRT_PLC_DTL_ADR", "TSK_ST_DTTM",
                    "TSK_ED_DTTM", "SIL_OFC_TLNO", "SIL_OFC_GUD_FCTS"))) {
                continue;
            }
            sources.add(detail("RECEPTION")
                    .receptionAddress(text(row, "CTRT_PLC_ADR"))
                    .receptionDetailAddress(text(row, "CTRT_PLC_DTL_ADR"))
                    .operationBegin(text(row, "TSK_ST_DTTM"))
                    .operationEnd(text(row, "TSK_ED_DTTM"))
                    .phone(text(row, "SIL_OFC_TLNO"))
                    .receptionGuidance(text(row, "SIL_OFC_GUD_FCTS"))
                    .build());
        }
    }

    private void addAnnouncementFiles(
            List<LhAnnouncementDetailSourceSnapshot> sources,
            JsonNode root
    ) {
        for (JsonNode row : rows(root, "dsAhflInfo")) {
            sources.add(detail("ANNOUNCEMENT_FILE")
                    .kind(text(row, "SL_PAN_AHFL_DS_CD_NM"))
                    .name(text(row, "CMN_AHFL_NM"))
                    .url(text(row, "AHFL_URL"))
                    .build());
        }
    }

    private void addComplexImages(
            List<LhAnnouncementDetailSourceSnapshot> sources,
            JsonNode root
    ) {
        for (JsonNode row : rows(root, "dsSbdAhfl")) {
            sources.add(detail("COMPLEX_IMAGE")
                    .kind(text(row, "LS_SPL_INF_UPL_FL_DS_CD_NM", "SL_PAN_AHFL_DS_CD_NM"))
                    .name(text(row, "CMN_AHFL_NM"))
                    .url(text(row, "AHFL_URL"))
                    .attachmentComplexName(text(row, "LCC_NT_NM", "BZDT_NM"))
                    .build());
        }
    }

    private DetailBuilder detail(String datasetType) {
        return new DetailBuilder(datasetType);
    }

    private String text(JsonNode row, String field) {
        return LhResponseDatasetReader.text(row, field);
    }

    private String text(JsonNode row, String field, String alternativeField) {
        String value = text(row, field);
        if (value == null || value.isBlank()) {
            return text(row, alternativeField);
        }
        return value;
    }

    private List<JsonNode> rows(JsonNode root, String key) {
        if (!containsDataset(root, key)) {
            return List.of();
        }
        var rows = new ArrayList<JsonNode>();
        LhResponseDatasetReader.require(root, key).forEach(rows::add);
        return List.copyOf(rows);
    }

    private boolean hasContent(LhAnnouncementDetailSourceSnapshot source) {
        try {
            source.validateContent();
            return true;
        } catch (IllegalArgumentException failure) {
            return false;
        }
    }

    private static final class DetailBuilder {

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
        private String applicationBeginDate;
        private String applicationEndDate;
        private String winnerAnnouncementDate;
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

        private DetailBuilder(String datasetType) {
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

        private LhAnnouncementDetailSourceSnapshot build() {
            return new LhAnnouncementDetailSourceSnapshot(
                    datasetType,
                    complexName,
                    address,
                    detailAddress,
                    totalUnitCount,
                    heatingDescription,
                    exclusiveAreaRange,
                    expectedMoveInYearMonth,
                    guidanceText,
                    applicationPeriod,
                    applicationBeginDate,
                    applicationEndDate,
                    winnerAnnouncementDate,
                    documentTargetAnnouncementDate,
                    documentSubmissionBeginDate,
                    documentSubmissionEndDate,
                    contractBeginDate,
                    contractEndDate,
                    receptionAddress,
                    receptionDetailAddress,
                    operationBegin,
                    operationEnd,
                    phone,
                    receptionGuidance,
                    kind,
                    name,
                    url,
                    attachmentComplexName,
                    correctionReason,
                    etcContents);
        }
    }
}
