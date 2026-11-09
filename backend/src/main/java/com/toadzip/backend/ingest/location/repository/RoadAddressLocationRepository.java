package com.toadzip.backend.ingest.location.repository;

import com.toadzip.backend.ingest.location.domain.RoadAddressLocation;
import com.toadzip.backend.ingest.location.domain.RoadAddressLocationId;
import jakarta.persistence.QueryHint;
import java.util.List;
import org.hibernate.jpa.HibernateHints;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.QueryHints;

public interface RoadAddressLocationRepository
        extends JpaRepository<RoadAddressLocation, RoadAddressLocationId> {

    // 좌표 적재는 LocationSummaryStore의 JDBC COPY·병합으로 즉시 반영된다.
    // 조회마다 대량 원천 그래프의 cascade/dirty 검사를 반복하지 않고 나머지 변경은 커밋 시 저장한다.
    @QueryHints(@QueryHint(name = HibernateHints.HINT_FLUSH_MODE, value = "COMMIT"))
    List<RoadAddressLocation> findAllByNormalizedRoadAddressOrderByIdEntranceSerialAsc(
            String normalizedRoadAddress
    );
}
