package com.toadzip.backend.ingest.collection.gh.repository;

import com.toadzip.backend.ingest.collection.gh.domain.GhAnnouncementSnapshot;
import com.toadzip.backend.ingest.collection.gh.domain.GhAnnouncementBoard;
import com.toadzip.backend.ingest.collection.gh.domain.GhAnnouncementSource;
import com.toadzip.backend.ingest.collection.gh.dto.GhAnnouncementPage.Entry;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
public class GhAnnouncementStore {

    private final GhAnnouncementSourceRepository repository;
    private final Clock clock;
    private final GhHousingTypeDetails housingTypeDetails;

    @Transactional
    public void store(GhAnnouncementSnapshot snapshot) {
        Instant now = clock.instant();
        var existing = repository.findBySourceKey(snapshot.sourceKey());
        if (existing.isEmpty()) {
            repository.save(GhAnnouncementSource.from(snapshot, now));
            return;
        }
        GhAnnouncementSource source = existing.orElseThrow();
        if (source.getTitle().equals(snapshot.title()) && source.getPostedDate().equals(snapshot.postedDate())) {
            snapshot = snapshot.withDistricts(housingTypeDetails.carryOver(snapshot.districts(), source.getDistricts()));
        }
        source.updateFrom(snapshot, now);
    }

    @Transactional
    public void updateListValues(List<Entry> entries) {
        Map<String, Entry> byPbancNo = entries.stream().collect(Collectors.toMap(Entry::pbancNo, Function.identity()));
        for (GhAnnouncementSource source : repository.findAllByPbancNoIn(byPbancNo.keySet())) {
            Entry entry = byPbancNo.get(source.getPbancNo());
            source.updateListValues(entry.region(), entry.closingDate(), entry.status());
        }
    }

    /** 같은 게시판에 모든 구획을 읽어 저장했고 목록의 제목·게시일이 저장값과 같은 공고 번호. */
    @Transactional(readOnly = true)
    public Set<String> findUnchangedPbancNos(GhAnnouncementBoard board, List<Entry> entries) {
        Map<String, GhAnnouncementSource> stored = repository.findAllByPbancNoIn(
                        entries.stream().map(Entry::pbancNo).toList())
                .stream().collect(Collectors.toMap(GhAnnouncementSource::getPbancNo, Function.identity()));
        Set<String> unchanged = new HashSet<>();
        for (Entry entry : entries) {
            GhAnnouncementSource source = stored.get(entry.pbancNo());
            if (source != null && source.getBoard() == board && source.getTitle().equals(entry.title())
                    && source.getPostedDate().equals(entry.postedDate()) && source.hasAllSections()) {
                unchanged.add(entry.pbancNo());
            }
        }
        return unchanged;
    }
}
