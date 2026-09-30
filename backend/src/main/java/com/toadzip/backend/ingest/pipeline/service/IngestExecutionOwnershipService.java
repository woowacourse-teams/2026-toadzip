package com.toadzip.backend.ingest.pipeline.service;

import com.toadzip.backend.ingest.exception.exception.IngestAlreadyRunningException;
import com.toadzip.backend.ingest.pipeline.repository.DataPipelineExecutionLock;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Service;

@Service
public class IngestExecutionOwnershipService {

    private final DataPipelineExecutionLock executionLock;

    public IngestExecutionOwnershipService(DataPipelineExecutionLock executionLock) {
        this.executionLock = executionLock;
    }

    public Execution acquire() {
        var lease = executionLock.tryAcquire().orElseThrow(() -> new IngestAlreadyRunningException(
                "다른 데이터 수집·정제 작업이 이미 실행 중입니다."
        ));
        return new Execution(lease, IngestExecutionScope.open(lease));
    }

    public static final class Execution implements AutoCloseable {

        private final DataPipelineExecutionLock.Lease lease;
        private final IngestExecutionScope scope;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Execution(DataPipelineExecutionLock.Lease lease, IngestExecutionScope scope) {
            this.lease = lease;
            this.scope = scope;
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            try {
                scope.close();
            }
            finally {
                lease.close();
            }
        }
    }
}
