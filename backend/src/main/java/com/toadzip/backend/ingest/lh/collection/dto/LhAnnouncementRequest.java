package com.toadzip.backend.ingest.lh.collection.dto;

import com.toadzip.backend.ingest.lh.collection.announcementcatalog.domain.LhAnnouncementCatalogSourceView;
import com.toadzip.backend.ingest.lh.collection.domain.LhAnnouncementQuery;
import java.net.URI;
import java.util.Optional;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriComponentsBuilder;

public record LhAnnouncementRequest(
        String panId,
        String connectionSystemDivisionCode,
        String upperAnnouncementTypeCode,
        String announcementTypeCode,
        String supplyInfoTypeCode
) {

    public static final int COLLECTION_VERSION = 6;

    /**
     * 공고목록 행의 조회 조건. 수집 후보·정제·PDF 보정이 같은 공고목록 행에서 같은 요청(원천 행을 찾는 요청 해시)을
     * 만들도록 이곳에서만 만든다.
     */
    public static LhAnnouncementRequest from(LhAnnouncementCatalogSourceView catalog) {
        return new LhAnnouncementRequest(catalog.getPanId(), catalog.getConnectionSystemDivisionCode(),
                catalog.getUpperAnnouncementTypeCode(), catalog.getAnnouncementTypeCode(), catalog.getSupplyInfoTypeCode());
    }

    public static Optional<LhAnnouncementRequest> from(URI detailUrl, String supplyInfoTypeCode) {
        MultiValueMap<String, String> query = UriComponentsBuilder.fromUri(detailUrl)
                .build()
                .getQueryParams();
        String panId = query.getFirst("panId");
        String connection = query.getFirst("ccrCnntSysDsCd");
        String upperType = query.getFirst("uppAisTpCd");
        String announcementType = query.getFirst("aisTpCd");
        if (isBlank(panId) || isBlank(connection) || isBlank(upperType) || isBlank(supplyInfoTypeCode)) {
            return Optional.empty();
        }
        return Optional.of(new LhAnnouncementRequest(
                panId,
                connection,
                upperType,
                blankToNull(announcementType),
                supplyInfoTypeCode
        ));
    }

    /** 이 요청이 외부 API에 보내는 조회 조건. 파라미터와 요청 설명은 조회 조건 하나에서 만든다. */
    public LhAnnouncementQuery query() {
        return new LhAnnouncementQuery(panId, connectionSystemDivisionCode, upperAnnouncementTypeCode,
                announcementTypeCode, supplyInfoTypeCode);
    }

    public String requestDescription() {
        return query().description(COLLECTION_VERSION);
    }

    public String requestHash() {
        return LhAnnouncementQuery.requestHashOf(requestDescription());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String blankToNull(String value) {
        if (isBlank(value)) {
            return null;
        }
        return value;
    }
}
