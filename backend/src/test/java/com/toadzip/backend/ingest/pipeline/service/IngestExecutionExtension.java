package com.toadzip.backend.ingest.pipeline.service;

import com.toadzip.backend.ingest.pipeline.repository.DataPipelineExecutionLock;
import com.toadzip.backend.ingest.pipeline.repository.DataPipelineExecutionLock.Lease;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Arrays;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.extension.AfterTestExecutionCallback;
import org.junit.jupiter.api.extension.BeforeTestExecutionCallback;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;
import org.junit.platform.commons.support.AnnotationSupport;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/**
 * 수집·정제 진입점은 실행 소유권 안에서만 돈다. 진입점을 직접 부르는 테스트는 테스트 본문을 운영처럼 실행 소유권 안에서 돌린다.
 * Spring 테스트는 실제 실행 잠금으로 소유권을 잡고, 그 밖의 테스트는 늘 잡힌 소유권으로 실행 범위만 연다.
 */
public class IngestExecutionExtension implements BeforeTestExecutionCallback, AfterTestExecutionCallback {

    private static final Namespace NAMESPACE = Namespace.create(IngestExecutionExtension.class);
    private static final String EXECUTION = "execution";

    /** 비동기 실행처럼 테스트 대상이 스스로 실행 소유권을 잡는 테스트는 확장이 소유권을 잡지 않는다. */
    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    public @interface AcquiresOwnExecution {
    }

    @Override
    public void beforeTestExecution(ExtensionContext context) {
        if (context.getRequiredTestMethod().isAnnotationPresent(AcquiresOwnExecution.class)) {
            return;
        }
        var executionLock = isSpringTest(context)
                ? SpringExtension.getApplicationContext(context).getBeanProvider(DataPipelineExecutionLock.class)
                        .getIfAvailable()
                : null;
        Lease lease = executionLock == null ? new HeldLease() : executionLock.tryAcquire().orElseThrow();
        context.getStore(NAMESPACE).put(EXECUTION, new Execution(lease, IngestExecutionScope.open(lease)));
    }

    @Override
    public void afterTestExecution(ExtensionContext context) {
        Execution execution = context.getStore(NAMESPACE).remove(EXECUTION, Execution.class);
        if (execution != null) {
            execution.close();
        }
    }

    /** HTTP 요청처럼 스스로 실행 소유권을 잡는 경로와 섞인 테스트는 진입점을 직접 부를 때만 실행 소유권을 잡는다. */
    public static <T> T owned(IngestExecutionOwnershipService ownership, Supplier<T> entry) {
        try (var ignored = ownership.acquire()) {
            return entry.get();
        }
    }

    private boolean isSpringTest(ExtensionContext context) {
        return AnnotationSupport.findRepeatableAnnotations(context.getRequiredTestClass(), ExtendWith.class).stream()
                .anyMatch(extendWith -> Arrays.asList(extendWith.value()).contains(SpringExtension.class));
    }

    private record Execution(Lease lease, IngestExecutionScope scope) {

        private void close() {
            try {
                scope.close();
            }
            finally {
                lease.close();
            }
        }
    }

    private static final class HeldLease implements Lease {

        private final UUID ownerId = UUID.randomUUID();

        @Override
        public UUID ownerId() {
            return ownerId;
        }

        @Override
        public long generation() {
            return 1;
        }

        @Override
        public void verifyHeld() {
        }

        @Override
        public void close() {
        }
    }
}
