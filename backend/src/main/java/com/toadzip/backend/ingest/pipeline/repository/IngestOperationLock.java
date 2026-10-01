package com.toadzip.backend.ingest.pipeline.repository;

import com.toadzip.backend.global.persistence.PostgresAdvisoryLock;
import java.sql.SQLException;
import java.util.Optional;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.stereotype.Repository;

/** 동기 수집·정제 작업의 실행 기간 동안 기존 DB 잠금 키를 유지한다. */
@Repository
public class IngestOperationLock {

    private final PostgresAdvisoryLock databaseLock;

    public IngestOperationLock(DataSource dataSource) {
        databaseLock = new PostgresAdvisoryLock(dataSource);
    }

    public <T> Optional<T> tryRun(Operation operation, Supplier<T> action) {
        try {
            Optional<PostgresAdvisoryLock.Lease> lease = databaseLock.tryAcquire(
                    operation.key, operation.lockName
            );
            if (lease.isEmpty()) {
                return Optional.empty();
            }
            try (PostgresAdvisoryLock.Lease ignored = lease.orElseThrow()) {
                return Optional.of(action.get());
            }
        }
        catch (SQLException exception) {
            throw new IllegalStateException(operation.failureMessage, exception);
        }
    }

    public enum Operation {
        LH_ANNOUNCEMENT_COLLECTION(
                8_432_026_082_400_001L, "LH 공고 수집 실행", "LH 공고 수집 실행 잠금을 처리하지 못했습니다."
        ),
        MYHOME_ANNOUNCEMENT_COLLECTION(
                8_432_026_082_800_017L, "마이홈 공고 수집 실행", "마이홈 공고 수집 실행 잠금을 처리하지 못했습니다."
        ),
        MYHOME_COMPLEX_MAPPING(
                8_432_026_082_400_003L, "마이홈 단지 매핑 실행", "마이홈 단지 매핑 실행 잠금을 처리하지 못했습니다."
        ),
        // 같은 제품 공고를 수정하는 두 작업은 DB 잠금을 공유한다.
        MYHOME_ANNOUNCEMENT_MAPPING(
                8_432_026_082_800_018L, "마이홈 공고 매핑 실행", "마이홈 공고 매핑 실행 잠금을 처리하지 못했습니다."
        ),
        LH_ANNOUNCEMENT_ENRICHMENT(
                8_432_026_082_800_018L, "LH 공고 보강 실행", "LH 공고 보강 잠금을 확인할 수 없습니다."
        );

        private final long key;
        private final String lockName;
        private final String failureMessage;

        Operation(long key, String lockName, String failureMessage) {
            this.key = key;
            this.lockName = lockName;
            this.failureMessage = failureMessage;
        }
    }
}
