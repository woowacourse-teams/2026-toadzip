package com.toadzip.backend.ingest.collection.lh.announcementcatalog.repository;

import com.toadzip.backend.ingest.collection.lh.announcementcatalog.domain.LhAnnouncementCatalogPage;
import com.toadzip.backend.ingest.collection.lh.announcementcatalog.domain.LhAnnouncementCatalogRow;
import com.toadzip.backend.ingest.collection.lh.announcementcatalog.domain.LhAnnouncementCatalogSnapshot;
import com.toadzip.backend.ingest.collection.lh.repository.LhResponseDatasetReader;
import com.toadzip.backend.ingest.collection.lh.repository.external.LhResponseStatusValidator;
import com.toadzip.backend.ingest.collection.paging.domain.SourcePage;
import com.toadzip.backend.ingest.collection.repository.external.ExternalDataRequestException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
public class LhAnnouncementCatalogPageParser {

    public LhAnnouncementCatalogPage parse(JsonNode root, int page, int pageSize) {
        new LhResponseStatusValidator().validate(root);
        if (!root.isArray()) {
            throw invalid("목록 응답 구조");
        }
        JsonNode header = LhResponseDatasetReader.require(root, "resHeader");
        if (header.size() != 1) {
            throw invalid("응답 헤더");
        }
        JsonNode search = LhResponseDatasetReader.require(root, "dsSch");
        JsonNode rows = LhResponseDatasetReader.require(root, "dsList");
        if (!search.isArray() || search.size() != 1 || !rows.isArray() || rows.size() > pageSize) {
            throw invalid("검색 조건 또는 목록 행");
        }
        JsonNode condition = search.get(0);
        if (number(condition, "PAGE") != page || number(condition, "PG_SZ") != pageSize) {
            throw invalid("요청과 다른 페이지");
        }
        String startDate = required(condition, "PAN_ST_DT");
        String endDate = required(condition, "PAN_ED_DT");
        if (rows.isEmpty()) {
            throw invalid("전체 건수를 확인할 수 없는 빈 목록");
        }
        int total = number(rows.get(0), "ALL_CNT");
        List<LhAnnouncementCatalogRow> entries = new ArrayList<>();
        for (int index = 0; index < rows.size(); index++) {
            JsonNode row = rows.get(index);
            if (number(row, "ALL_CNT") != total || number(row, "RNUM") != (long) (page - 1) * pageSize + index + 1) {
                throw invalid("전체 건수 또는 행 순번 불일치");
            }
            entries.add(entry(row));
        }
        if ((long) (page - 1) * pageSize + rows.size() > total) {
            throw invalid("전체 건수보다 많은 행");
        }
        return new LhAnnouncementCatalogPage(new SourcePage<>(total, entries), startDate, endDate);
    }

    private LhAnnouncementCatalogRow entry(JsonNode row) {
        LhAnnouncementCatalogSnapshot snapshot = new LhAnnouncementCatalogSnapshot(
                text(row, "PAN_ID"), text(row, "CCR_CNNT_SYS_DS_CD"), text(row, "UPP_AIS_TP_CD"),
                text(row, "AIS_TP_CD"), text(row, "SPL_INF_TP_CD"),
                text(row, "PAN_NM"), text(row, "PAN_SS"), text(row, "PAN_NT_ST_DT"), text(row, "PAN_DT"),
                text(row, "CLSG_DT"), text(row, "DTL_URL"), text(row, "DTL_URL_MOB")
        );
        return new LhAnnouncementCatalogRow(snapshot, row.toString());
    }

    private String required(JsonNode row, String field) {
        String value = text(row, field);
        if (value == null || value.isBlank()) {
            throw invalid(field + " 누락");
        }
        return value.strip();
    }

    private String text(JsonNode row, String field) {
        return LhResponseDatasetReader.text(row, field);
    }

    private int number(JsonNode row, String field) {
        try {
            int value = Integer.parseInt(required(row, field));
            if (value < 0) {
                throw invalid(field + " 음수");
            }
            return value;
        }
        catch (NumberFormatException exception) {
            throw invalid(field + " 숫자 형식");
        }
    }

    private ExternalDataRequestException invalid(String reason) {
        return new ExternalDataRequestException("LH 공고 목록이 불완전합니다: " + reason);
    }
}
