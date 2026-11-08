package com.toadzip.backend.ingest.refinement.service;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.AnnouncementPublicationType;
import com.toadzip.backend.announcement.repository.AnnouncementRepository;
import com.toadzip.backend.ingest.refinement.domain.BoardRevisionName;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * SH·GH 정정공고를 원공고에 잇는다. 정정 표시를 뗀 이름이 같고, 같은 기관이며, 원공고 공고일이 정정공고보다 늦지 않고 90일 안이며,
 * 다른 공고가 이어 가지 않은 공고 중 가장 최근 것이 하나일 때만 잇는다. 하나로 정해지지 않으면 잇지 않고 관리자 확정에 맡긴다.
 */
@Component
@RequiredArgsConstructor
public class BoardRevisionLinker {

    static final int MAX_DAYS_BEFORE_REVISION = 90;

    private final AnnouncementRepository announcements;

    /** nameRule은 기관의 정정 표시 규칙이다. 정정공고와 후보 공고명에 같은 규칙을 쓴다. */
    public LinkResult link(Announcement revision, Function<String, BoardRevisionName> nameRule) {
        BoardRevisionName name = nameRule.apply(revision.getName());
        if (!name.isRevision()) {
            return LinkResult.NOT_REVISION;
        }
        if (revision.getPreviousAnnouncement() != null) {
            return LinkResult.ALREADY_LINKED;
        }
        String base = name.baseName();
        LocalDate posted = revision.getPostedDate();
        List<Announcement> candidates = announcements.findLinkableOriginals(revision.getProvider(), revision.getId(),
                posted.minusDays(MAX_DAYS_BEFORE_REVISION), posted).stream()
                .filter(candidate -> nameRule.apply(candidate.getName()).baseName().equals(base))
                .filter(candidate -> !descendsFrom(candidate, revision))
                .toList();
        LocalDate latest = candidates.stream().map(Announcement::getPostedDate).max(Comparator.naturalOrder())
                .orElse(null);
        List<Announcement> newest = candidates.stream()
                .filter(candidate -> candidate.getPostedDate().equals(latest)).toList();
        if (newest.size() != 1) {
            return LinkResult.UNLINKED;
        }
        if (revision.linkRevision(newest.getFirst(), AnnouncementPublicationType.CORRECTION)) {
            return LinkResult.LINKED;
        }
        return LinkResult.ALREADY_LINKED;
    }

    /** 이미 정정공고를 거쳐 이어진 공고를 원공고로 고르면 서로를 가리키는 순환이 생기므로 후손은 후보에서 뺀다. */
    private boolean descendsFrom(Announcement candidate, Announcement revision) {
        for (Announcement current = candidate.getPreviousAnnouncement(); current != null;
                current = current.getPreviousAnnouncement()) {
            if (current.getId().equals(revision.getId())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 마무리에서 정제 때 원공고를 못 찾은 정정공고를 다시 잇는다. 공고마다 따로 커밋하고, 집계에는 커밋된 결과만 반영해
     * 되돌아간 공고가 연결 수에 남지 않게 한다.
     */
    public void relinkUnlinked(SourceRefinementRun<?> run, BoardRevisionTally tally,
            Function<String, BoardRevisionName> nameRule) {
        Map<String, LinkResult> results = new HashMap<>();
        run.apply(tally.unlinkedKeys(), key -> key, key -> {
            Announcement revision = announcements.findBySourceAnnouncementIdentifier(key)
                    .flatMap(found -> announcements.findByIdForUpdate(found.getId())).orElse(null);
            if (revision == null || revision.isAdminDeleted()) {
                return false;
            }
            results.put(key, link(revision, nameRule));
            return true;
        }, key -> tally.record(key, results.get(key)));
    }

    public enum LinkResult {
        NOT_REVISION, ALREADY_LINKED, LINKED, UNLINKED
    }
}
