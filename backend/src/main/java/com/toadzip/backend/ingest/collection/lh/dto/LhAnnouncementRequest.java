package com.toadzip.backend.ingest.collection.lh.dto;

import com.toadzip.backend.ingest.collection.lh.domain.LhAnnouncementQuery;
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
