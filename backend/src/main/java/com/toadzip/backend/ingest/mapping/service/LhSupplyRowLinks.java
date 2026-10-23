package com.toadzip.backend.ingest.mapping.service;

import com.toadzip.backend.ingest.collection.lh.supply.domain.LhAnnouncementSupplySource;
import com.toadzip.backend.ingest.collection.myhome.announcement.domain.projection.MyHomeAnnouncementSource;
import com.toadzip.backend.ingest.mapping.domain.LhSupplyRowLink;
import com.toadzip.backend.ingest.mapping.repository.LhSupplyRowLinkRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 관리자가 저장한 LH 공급 연결을 정제와 LH 보강이 같은 기준으로 판정한다.
 * 저장 당시와 원천 지문이 같고 두 원천행이 지금도 있을 때만 유효하다.
 */
@Component
@RequiredArgsConstructor
public class LhSupplyRowLinks {
    private final LhSupplyRowLinkRepository links;
    private final ObjectMapper json;

    public static String identifier(LhAnnouncementSupplySource source) {
        return "LH:" + source.getPanId() + ":SUPPLY:" + source.getSourceOrder();
    }

    /** 공고의 저장 연결을 LH 행 식별자별로 판정한다. 저장 연결이 없는 LH 행은 결과에 없다. */
    public Map<String, State> states(String announcementIdentifier, List<MyHomeAnnouncementSource> currentSources,
            List<LhAnnouncementSupplySource> supplies) {
        var result = new LinkedHashMap<String, State>();
        for (var link : links.findAllBySourceAnnouncementIdentifierOrderById(announcementIdentifier)) {
            result.put(link.getLhSupplyRowIdentifier(), new State(link, valid(link, currentSources, supplies)));
        }
        return result;
    }

    private boolean valid(LhSupplyRowLink link, List<MyHomeAnnouncementSource> currentSources,
            List<LhAnnouncementSupplySource> supplies) {
        boolean sameSupply = supplies.stream().anyMatch(value ->
                identifier(value).equals(link.getLhSupplyRowIdentifier())
                        && lhFingerprint(value).equals(link.getLhSourceFingerprint()));
        boolean sameSource = currentSources.stream().anyMatch(value ->
                value.getSourceKey().equals(link.getMyhomeSourceRowIdentifier())
                        && myHomeFingerprint(value).equals(link.getMyhomeSourceFingerprint()));
        return sameSupply && sameSource;
    }

    /** 조회 이후 원천이나 저장 연결이 바뀌었는지 확인하는 값이다. */
    String token(List<MyHomeAnnouncementSource> currentSources, List<LhAnnouncementSupplySource> supplies,
            Map<String, State> states) {
        var values = new java.util.ArrayList<Object>();
        currentSources.forEach(source -> values.add(myHomeFingerprint(source)));
        supplies.forEach(supply -> values.add(lhFingerprint(supply)));
        states.values().forEach(state -> values.add(state.link().getId() + ":" + state.link().getVersion()));
        return hash(values.toArray());
    }

    /** 연결 판단에 쓰는 LH 값만 담는다. 보증금·임대료처럼 보강으로 갱신되는 값은 재확인 사유가 아니다. */
    String lhFingerprint(LhAnnouncementSupplySource source) {
        return hash(source.getPanId(), source.getRequestHash(), source.getSourceOrder(), source.getComplexLabel(),
                source.getTypeName(), source.getExclusiveArea(), source.getSupplyArea(), source.getTotalUnitCount(),
                source.getSuppliedUnitCount());
    }

    String myHomeFingerprint(MyHomeAnnouncementSource source) {
        return hash(source.getSourceKey(), source.getHsmpNm(), source.getHouseTyNm(), source.getPnu(),
                source.getSuplyTyNm(), source.getSumSuplyCo(), source.getUrl());
    }

    private String hash(Object... values) {
        var texts = Arrays.stream(values).map(value -> java.util.Objects.toString(value, "")).toList();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(json.writeValueAsString(texts).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", exception);
        }
    }

    public record State(LhSupplyRowLink link, boolean valid) {
        public String myhomeSourceRowIdentifier() {
            return link.getMyhomeSourceRowIdentifier();
        }
    }
}
