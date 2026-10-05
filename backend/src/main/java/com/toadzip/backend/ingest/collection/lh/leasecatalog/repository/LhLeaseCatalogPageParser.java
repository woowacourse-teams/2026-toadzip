package com.toadzip.backend.ingest.collection.lh.leasecatalog.repository;

import com.toadzip.backend.ingest.collection.lh.leasecatalog.domain.LhCatalogSourceSnapshot;
import com.toadzip.backend.ingest.collection.lh.leasecatalog.dto.LhLeaseCatalogCollectionRequest;
import com.toadzip.backend.ingest.collection.lh.repository.LhResponseDatasetReader;
import com.toadzip.backend.ingest.collection.paging.domain.SourcePage;
import com.toadzip.backend.ingest.collection.repository.external.ExternalDataRequestException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
public class LhLeaseCatalogPageParser {

    public SourcePage<LhCatalogSourceSnapshot> parse(
            JsonNode response, LhLeaseCatalogCollectionRequest request, int page
    ) {
        JsonNode header = LhResponseDatasetReader.require(response, "resHeader");
        JsonNode search = LhResponseDatasetReader.require(response, "dsSch");
        JsonNode rows = LhResponseDatasetReader.require(response, "dsList");
        if (header.size() != 1 || !"Y".equals(header.get(0).path("SS_CODE").asString())
                || search.size() != 1 || rows.size() > request.pageSize()) {
            throw invalid("헤더 또는 검색 조건·행 개수");
        }
        if (number(search.get(0), "PAGE") != page || number(search.get(0), "PG_SZ") != request.pageSize()) {
            throw invalid("요청과 다른 페이지");
        }
        if (rows.isEmpty()) {
            throw invalid("전체 건수를 확인할 수 없는 빈 목록");
        }
        int totalCount = number(rows.get(0), "ALL_CNT");
        List<LhCatalogSourceSnapshot> snapshots = new ArrayList<>();
        for (int index = 0; index < rows.size(); index++) {
            JsonNode row = rows.get(index);
            if (number(row, "ALL_CNT") != totalCount
                    || number(row, "RNUM") != (long) (page - 1) * request.pageSize() + index + 1) {
                throw invalid("전체 건수 또는 행 순번 불일치");
            }
            snapshots.add(snapshot(row));
        }
        if ((long) (page - 1) * request.pageSize() + rows.size() > totalCount) {
            throw invalid("전체 건수보다 많은 행");
        }
        return new SourcePage<>(totalCount, snapshots);
    }

    private LhCatalogSourceSnapshot snapshot(JsonNode row) {
        LhCatalogSourceSnapshot snapshot = new LhCatalogSourceSnapshot(text(row, "ARA_NM"), text(row, "AIS_TP_CD_NM"),
                text(row, "SBD_LGO_NM"), text(row, "SUM_HSH_CNT"), text(row, "DDO_AR"), text(row, "HSH_CNT"),
                text(row, "LS_GMY"), text(row, "RFE"));
        try {
            snapshot.validateIdentifiers();
        } catch (IllegalArgumentException failure) {
            throw new ExternalDataRequestException(failure.getMessage(), failure);
        }
        return snapshot;
    }

    private int number(JsonNode row, String field) {
        try {
            int value = Integer.parseInt(row.path(field).asString("").strip());
            if (value < 0) {
                throw invalid(field + " 음수");
            }
            return value;
        } catch (NumberFormatException failure) {
            throw invalid(field + " 숫자 형식");
        }
    }

    private String text(JsonNode row, String field) {
        return LhResponseDatasetReader.text(row, field);
    }

    private ExternalDataRequestException invalid(String reason) {
        return new ExternalDataRequestException("LH 임대 카탈로그 응답이 불완전합니다: " + reason);
    }
}
