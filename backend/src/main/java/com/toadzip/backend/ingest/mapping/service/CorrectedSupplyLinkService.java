package com.toadzip.backend.ingest.mapping.service;

import com.toadzip.backend.housing.domain.HousingType;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 공고 보정에서 바꾼 공급행 연결을 원천행별 저장 매칭으로 남긴다. 재정제는 자동 매칭보다 이 매칭을 먼저 쓴다.
 * 원천에 없는 행과 분할 보정 행은 대상이 아니다. 연결을 지운 행은 저장 매칭도 해제한다.
 * 원천을 해석할 수 없거나 원천과 공급유형이 다른 단지라 정제가 쓸 수 없는 연결은 저장하지 않는다.
 */
@Service
@RequiredArgsConstructor
public class CorrectedSupplyLinkService {
    private final SupplyCorrectionSources sources;
    private final MyHomeAnnouncementSupplyMatcher matcher;
    private final SavedSupplyMatches savedMatches;

    @Transactional
    public void remember(String identifier, Map<String, HousingType> links, String sourceUrl, String reason,
            String actor) {
        if (identifier == null || links.isEmpty()) {
            return;
        }
        var sourceRows = sources.findSupplyRows(identifier).orElse(null);
        if (sourceRows == null) {
            return;
        }
        for (var data : sourceRows) {
            String rowIdentifier = data.sourceSupplyRowIdentifier();
            if (!links.containsKey(rowIdentifier) || sources.isSplit(data)) {
                continue;
            }
            var type = links.get(rowIdentifier);
            if (type == null) {
                savedMatches.release(rowIdentifier);
                continue;
            }
            var match = matcher.matchSelected(data, type.getHousingComplex().getId(), type.getId());
            if (match.failure() != null) {
                continue;
            }
            savedMatches.confirm(data, match.complex(), match.housingType(), sourceUrl.strip(), reason.strip(), actor);
        }
    }
}
