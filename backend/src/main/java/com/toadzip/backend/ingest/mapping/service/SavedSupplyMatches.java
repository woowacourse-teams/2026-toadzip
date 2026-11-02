package com.toadzip.backend.ingest.mapping.service;

import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.domain.HousingType;
import com.toadzip.backend.housing.repository.HousingTypeRepository;
import com.toadzip.backend.ingest.mapping.domain.AnnouncementSupplyMatchDecision;
import com.toadzip.backend.ingest.mapping.dto.AnnouncementSupplyMatching.SavedMatch;
import com.toadzip.backend.ingest.mapping.repository.AnnouncementSupplyMatchDecisionRepository;
import com.toadzip.backend.ingest.mapping.service.MyHomeAnnouncementSourceMapper.MyHomeSupplyRowMappingData;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 관리자가 원천 공급행마다 저장한 매칭. 저장할 때 원천과 연결 대상의 지문을 남기고, 정제는 두 지문이 그대로일 때만
 * 저장한 매칭을 쓴다. 저장·유효성 판정·해제·분할 단지 통합 때의 이동을 이 모듈만 안다.
 */
@Component
@RequiredArgsConstructor
class SavedSupplyMatches {

    private final AnnouncementSupplyMatchDecisionRepository decisions;
    private final HousingTypeRepository housingTypes;
    private final SupplyMatchingFingerprint fingerprints;

    /** 저장한 매칭이 없으면 비어 있다. 있으면 원천과 연결 대상이 저장 이후 바뀌지 않았는지 함께 돌려준다. */
    Optional<Saved> find(MyHomeSupplyRowMappingData data) {
        return decisions.findByRowIdentifier(data.sourceSupplyRowIdentifier()).map(decision -> new Saved(
                decision.getComplexId(), decision.getHousingTypeId(),
                hasValidTarget(decision) && fingerprints.source(data).equals(decision.getSourceFingerprint())));
    }

    boolean references(HousingType type) {
        return decisions.existsByHousingTypeId(type.getId());
    }

    /** 관리자 화면이 조회한 뒤 원천이나 저장한 매칭이 바뀌었는지 확인하는 값이다. */
    String reviewToken(MyHomeSupplyRowMappingData data) {
        return fingerprints.review(data, decisions.findByRowIdentifier(data.sourceSupplyRowIdentifier()).orElse(null));
    }

    SavedMatch view(MyHomeSupplyRowMappingData data, boolean reviewRequired) {
        return decisions.findByRowIdentifier(data.sourceSupplyRowIdentifier())
                .map(value -> savedMatchOf(value, reviewRequired)).orElse(null);
    }

    /** 검증한 선택을 저장하고 저장한 내용을 돌려준다. 원천과 연결 대상의 지금 지문을 함께 남긴다. */
    SavedMatch confirm(MyHomeSupplyRowMappingData data, HousingComplex complex, HousingType housingType,
            String sourceUrl, String reason, String actor) {
        String rowIdentifier = data.sourceSupplyRowIdentifier();
        var decision = decisions.findByRowIdentifier(rowIdentifier)
                .orElseGet(() -> new AnnouncementSupplyMatchDecision(rowIdentifier));
        decision.confirm(complex.getId(), housingType.getId(), fingerprints.source(data),
                fingerprints.target(housingType), sourceUrl, reason, actor);
        return savedMatchOf(decisions.saveAndFlush(decision), false);
    }

    /** 저장한 매칭을 지우고, 이력에 남길 수 있게 지운 내용을 돌려준다. */
    Optional<SavedMatch> release(String rowIdentifier) {
        var decision = decisions.findByRowIdentifier(rowIdentifier);
        decision.ifPresent(value -> {
            decisions.delete(value);
            decisions.flush();
        });
        return decision.map(value -> savedMatchOf(value, false));
    }

    /** 분할 단지를 합치기 전에 연결 대상이 아직 유효한 매칭만 고른다. 합친 뒤 {@link #moveToComplex}로 옮긴다. */
    List<Long> validIn(Collection<Long> complexIds) {
        return decisions.findAllByComplexIdIn(complexIds).stream()
                .filter(this::hasValidTarget).map(AnnouncementSupplyMatchDecision::getId).toList();
    }

    /** 같은 주택형의 소속 단지만 바뀐 경우다. 확인자와 근거는 그대로 두고 대상 지문만 다시 남긴다. */
    void moveToComplex(List<Long> decisionIds, long complexId) {
        decisions.findAllById(decisionIds).forEach(decision -> decision.moveToComplex(complexId,
                fingerprints.target(housingTypes.findById(decision.getHousingTypeId()).orElseThrow())));
    }

    private static SavedMatch savedMatchOf(AnnouncementSupplyMatchDecision decision, boolean reviewRequired) {
        return new SavedMatch(decision.getComplexId(), decision.getHousingTypeId(), decision.getSourceUrl(),
                decision.getReason(), decision.getActor(), decision.getUpdatedAt(), reviewRequired);
    }

    private boolean hasValidTarget(AnnouncementSupplyMatchDecision decision) {
        HousingType type = decision.getHousingTypeId() == null ? null
                : housingTypes.findById(decision.getHousingTypeId()).orElse(null);
        return type != null && type.getHousingComplex().getId().equals(decision.getComplexId())
                && fingerprints.target(type).equals(decision.getTargetFingerprint());
    }

    record Saved(Long complexId, Long housingTypeId, boolean valid) {
    }
}
