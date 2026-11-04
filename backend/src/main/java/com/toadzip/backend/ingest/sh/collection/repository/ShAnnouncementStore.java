package com.toadzip.backend.ingest.sh.collection.repository;

import com.toadzip.backend.ingest.sh.collection.domain.ShAnnouncementSnapshot;
import com.toadzip.backend.ingest.sh.collection.domain.ShAnnouncementSource;
import com.toadzip.backend.ingest.sh.collection.dto.ShAnnouncementPage.Entry;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
public class ShAnnouncementStore {

    private final ShAnnouncementSourceRepository repository;
    private final Clock clock;

    @Transactional
    public void store(ShAnnouncementSnapshot snapshot) {
        Instant now = clock.instant();
        var existing = repository.findBySourceKey(snapshot.sourceKey());
        if (existing.isEmpty()) {
            repository.save(ShAnnouncementSource.from(snapshot, now));
            return;
        }
        existing.orElseThrow().updateFrom(snapshot, now);
    }

    /** 목록의 제목·등록일이 저장값과 같고 본문·첨부를 모두 가진 게시글의 seq와 마지막 수집 시각. */
    @Transactional(readOnly = true)
    public Map<String, Instant> findUnchangedCollectedAt(List<Entry> entries) {
        Map<String, ShAnnouncementSource> stored = repository.findAllBySeqIn(entries.stream().map(Entry::seq).toList())
                .stream().collect(Collectors.toMap(ShAnnouncementSource::getSeq, Function.identity()));
        Map<String, Instant> unchanged = new HashMap<>();
        for (Entry entry : entries) {
            ShAnnouncementSource source = stored.get(entry.seq());
            if (source != null && source.getBodyText() != null && source.getAttachments() != null
                    && source.getTitle().equals(entry.title())
                    && Objects.equals(source.getRegisteredDate(), entry.registeredDate())) {
                unchanged.put(entry.seq(), source.getCollectedAt());
            }
        }
        return unchanged;
    }
}
