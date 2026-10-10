package com.toadzip.backend.ingest.collection.fixture;

import com.toadzip.backend.ingest.collection.lh.detail.domain.LhAnnouncementDetailSource;
import com.toadzip.backend.ingest.collection.lh.detail.repository.LhDetailResponseParser;
import java.util.List;
import java.util.stream.IntStream;
import tools.jackson.databind.JsonNode;

/** 정제 테스트용 projection만 만든다. 응답 검증·파싱은 production 구현을 사용한다. */
public final class LhAnnouncementDetailFixtures {

    private LhAnnouncementDetailFixtures() {
    }

    public static List<LhAnnouncementDetailSource> sources(String panId, JsonNode response) {
        var snapshots = new LhDetailResponseParser().parse(response);
        return IntStream.range(0, snapshots.size()).mapToObj(index -> LhAnnouncementDetailSource.read(
                null, index, panId, null, null, snapshots.get(index))).toList();
    }
}
