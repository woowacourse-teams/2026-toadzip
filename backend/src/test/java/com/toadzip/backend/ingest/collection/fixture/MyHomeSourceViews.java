package com.toadzip.backend.ingest.collection.fixture;

import com.toadzip.backend.ingest.collection.myhome.announcement.domain.MyHomeAnnouncementSourceSnapshot;
import com.toadzip.backend.ingest.collection.myhome.announcement.domain.projection.MyHomeAnnouncementSourceView;
import com.toadzip.backend.ingest.collection.myhome.complex.domain.MyHomeComplexSourceSnapshot;
import com.toadzip.backend.ingest.collection.myhome.complex.domain.projection.MyHomeComplexSourceView;
import java.time.Instant;

/** 원천 행 Entity 없이 정제 테스트에 넘길 마이홈 원천 View를 만든다. 수명주기 값은 테스트가 직접 정한다. */
public final class MyHomeSourceViews {

    private MyHomeSourceViews() {
    }

    public static MyHomeAnnouncementSourceView announcement(int order, MyHomeAnnouncementSourceSnapshot snapshot) {
        return MyHomeAnnouncementSourceView.read(null, order, null, null, 0, true, snapshot);
    }

    public static MyHomeAnnouncementSourceView collectedAt(MyHomeAnnouncementSourceView source, Instant collectedAt) {
        return MyHomeAnnouncementSourceView.read(source.getId(), source.getSourceOrder(), collectedAt,
                source.getLastSeenRunId(), source.getConsecutiveMissCount(), source.isActive(), source.snapshot());
    }

    /** 같은 행이 바뀐 내용으로 다시 수집된 경우. id와 수명주기 값은 그대로 둔다. */
    public static MyHomeAnnouncementSourceView replaced(
            MyHomeAnnouncementSourceView source, MyHomeAnnouncementSourceSnapshot snapshot
    ) {
        return MyHomeAnnouncementSourceView.read(source.getId(), source.getSourceOrder(), source.getCollectedAt(),
                source.getLastSeenRunId(), source.getConsecutiveMissCount(), source.isActive(), snapshot);
    }

    /** 이번 실행에서 관찰된 행. */
    public static MyHomeAnnouncementSourceView seen(MyHomeAnnouncementSourceView source, String runId, Instant seenAt) {
        return MyHomeAnnouncementSourceView.read(source.getId(), source.getSourceOrder(), seenAt, runId, 0, true,
                source.snapshot());
    }

    /** 연속 2회 수집에서 빠져 비활성이 된 행. */
    public static MyHomeAnnouncementSourceView inactive(MyHomeAnnouncementSourceView source) {
        return MyHomeAnnouncementSourceView.read(source.getId(), source.getSourceOrder(), source.getCollectedAt(),
                source.getLastSeenRunId(), 2, false, source.snapshot());
    }

    public static MyHomeComplexSourceView complex(MyHomeComplexSourceSnapshot snapshot) {
        return MyHomeComplexSourceView.read(null, null, snapshot);
    }

    public static MyHomeComplexSourceView complex(MyHomeComplexSourceSnapshot snapshot, Instant collectedAt) {
        return MyHomeComplexSourceView.read(null, collectedAt, snapshot);
    }

    public static MyHomeComplexSourceView collectedAt(MyHomeComplexSourceView source, Instant collectedAt) {
        return MyHomeComplexSourceView.read(source.getId(), collectedAt, source.snapshot());
    }

    /** 같은 행이 바뀐 내용으로 다시 수집된 경우. */
    public static MyHomeComplexSourceView replaced(MyHomeComplexSourceView source, MyHomeComplexSourceSnapshot snapshot) {
        return MyHomeComplexSourceView.read(source.getId(), source.getCollectedAt(), snapshot);
    }
}
