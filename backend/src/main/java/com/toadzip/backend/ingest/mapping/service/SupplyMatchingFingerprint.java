package com.toadzip.backend.ingest.mapping.service;

import com.toadzip.backend.housing.domain.HousingType;
import com.toadzip.backend.ingest.collection.myhome.announcement.domain.projection.MyHomeAnnouncementSource;
import com.toadzip.backend.ingest.mapping.domain.AnnouncementSupplyMatchDecision;
import com.toadzip.backend.ingest.mapping.service.MyHomeAnnouncementSourceMapper.MyHomeSupplyRowMappingData;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class SupplyMatchingFingerprint {
    private final ObjectMapper json;

    SupplyMatchingFingerprint(ObjectMapper json) {
        this.json = json;
    }

    String source(MyHomeSupplyRowMappingData row) {
        return hash(List.of(row.sourceSupplyRowIdentifier(), row.source().getSourceKey(),
                row.sourceComplexName(), row.sourceHousingTypeName(), row.pnu(), row.complexSupplyType(),
                text(row.exclusiveArea()), text(row.supplyArea()), text(row.resolvedLhSourceIdentifier()),
                text(row.source().getUrl())));
    }

    public String target(HousingType type) {
        var complex = type.getHousingComplex();
        return hash(List.of(complex.getId(), complex.isAdminDeleted(), complex.getName(),
                complex.getAddress().getPnu(), complex.getSupplyType(), type.getId(), type.getName(),
                type.getExclusiveArea(), text(type.getSupplyArea())));
    }

    public String splitSource(MyHomeAnnouncementSource source) {
        return hash(List.of(source.getSourceKey(), text(source.getHsmpNm()), text(source.getHouseTyNm()),
                text(source.getPnu()), text(source.getSuplyTyNm()), text(source.getUrl()),
                text(source.getSumSuplyCo()), text(source.getRentGtn()), text(source.getMtRntchrg()),
                text(source.getEnty()), text(source.getSurlus())));
    }

    /** 공통 모집 묶음의 원천 지문. LH 공급정보로 펼친 행은 마이홈 원천에 그 LH 행의 표기·면적·공급 수를 더한다. */
    String splitSource(MyHomeSupplyRowMappingData row, boolean expanded) {
        if (!expanded) {
            return splitSource(row.source());
        }
        return hash(List.of(splitSource(row.source()), row.sourceSupplyRowIdentifier(), text(row.sourceHousingTypeName()),
                text(row.exclusiveArea()), text(row.supplyArea()), text(row.lhTotalSupplyHouseholdCount()),
                text(row.resolvedLhSourceIdentifier())));
    }

    String splitReview(String token, com.toadzip.backend.announcement.domain.SupplyRowGroup group) {
        if (group == null) {
            return token;
        }
        return hash(List.of(token, group.getId(), group.getVersion(), group.getAnnouncement().getVersion()));
    }

    String review(MyHomeSupplyRowMappingData row, AnnouncementSupplyMatchDecision decision) {
        if (decision == null) {
            return hash(row.matchingIdentity());
        }
        return hash(List.of(row.matchingIdentity(), decision.getId(), decision.getVersion()));
    }

    private String text(Object value) {
        if (value == null) {
            return "";
        }
        return value.toString();
    }

    private String hash(Object value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(json.writeValueAsString(value).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", exception);
        }
    }
}
