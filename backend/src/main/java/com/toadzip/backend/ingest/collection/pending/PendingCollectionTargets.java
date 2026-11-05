package com.toadzip.backend.ingest.collection.pending;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.pipeline.service.IngestWriteOwnershipGuard;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** 재시도할 요청 입력만 보관한다. 실행 소유권과 스케줄링은 기존 파이프라인이 맡는다. */
@Repository
@RequiredArgsConstructor
public class PendingCollectionTargets {
    private final EntityManager entities;
    private final ObjectMapper mapper;
    private final IngestWriteOwnershipGuard ownership;

    @Transactional
    public void remember(ExternalDataSource source, String key, Object request, int page) {
        rememberAll(source, Map.of(key, request), page);
    }

    @Transactional
    public void rememberAll(ExternalDataSource source, Map<String, ?> requests, int page) {
        if (requests.isEmpty()) {
            return;
        }
        ownership.verifyWrite();
        var existing = entities.createQuery("FROM PendingCollectionTarget WHERE source = :source AND targetKey IN :keys",
                        PendingCollectionTarget.class).setParameter("source", source)
                .setParameter("keys", requests.keySet()).getResultList().stream()
                .collect(Collectors.toMap(PendingCollectionTarget::getTargetKey, Function.identity()));
        requests.forEach((key, request) -> {
            String payload = mapper.writeValueAsString(request);
            if (existing.containsKey(key)) {
                existing.get(key).update(payload, page);
                return;
            }
            entities.persist(new PendingCollectionTarget(source, key, payload, page));
        });
    }

    @Transactional(readOnly = true)
    public List<PendingCollectionTarget> find(ExternalDataSource source) {
        return entities.createQuery("FROM PendingCollectionTarget WHERE source = :source ORDER BY id",
                PendingCollectionTarget.class).setParameter("source", source).getResultList();
    }

    public <T> T read(PendingCollectionTarget target, Class<T> type) {
        return mapper.readValue(target.getPayload(), type);
    }

    /** 원천 저장이 성공한 뒤에만 호출한다. 삭제 전에 중단되면 다음 실행에서 멱등 재수집한다. */
    @Transactional
    public void complete(ExternalDataSource source, String key) {
        ownership.verifyWrite();
        entities.createQuery("DELETE FROM PendingCollectionTarget WHERE source = :source AND targetKey = :key")
                .setParameter("source", source).setParameter("key", key).executeUpdate();
    }
}
