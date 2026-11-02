package com.toadzip.backend.ingest.mapping.service;

import com.toadzip.backend.admin.exception.AdminDataConflictException;
import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.repository.AnnouncementRepository;
import com.toadzip.backend.ingest.exception.exception.InvalidIngestRequestException;
import com.toadzip.backend.ingest.mapping.service.MyHomeAnnouncementSupplyRowResolver.ResolvedAnnouncement;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 관리자 공급 보정이 다시 정제로 덮어쓸 수 있는 공고인지 정하고, 정정·취소 공고가 이어 붙을 이전 공고를 찾는다. */
@Component
@RequiredArgsConstructor
class CorrectableAnnouncements {
    private final AnnouncementRepository announcements;

    /** 정제로 덮어쓸 수 있는 공고인지 확인하고, 연결할 이전 공고를 돌려준다. */
    Announcement writablePrevious(String identifier, ResolvedAnnouncement current, boolean splitting) {
        var existing = announcements.findBySourceAnnouncementIdentifierForUpdate(identifier).orElse(null);
        if (existing != null && existing.isAdminDeleted()) {
            throw new InvalidIngestRequestException("삭제된 공고입니다. 공고 관리에서 먼저 복구해 주세요.");
        }
        if (existing != null && existing.isAdminModified() && existing.getProvider() != current.data().provider()) {
            throw new InvalidIngestRequestException("관리자가 변경한 공고 기관이 원천과 다릅니다. 공고 관리에서 확인해 주세요.");
        }
        if (existing != null && splitting && existing.getProvider() != current.data().provider()) {
            throw new AdminDataConflictException("공고 기관이 원천과 다릅니다. 공고 관리에서 확인해 주세요.");
        }
        return previousOf(current);
    }

    /** 분할만 하는 보정은 삭제했거나 기관이 원천과 다른 공고를 건드리지 않는다. */
    void verifySplittable(String identifier, ResolvedAnnouncement current) {
        var existing = announcements.findBySourceAnnouncementIdentifierForUpdate(identifier).orElse(null);
        if (existing != null && (existing.isAdminDeleted() || existing.getProvider() != current.data().provider())) {
            throw new AdminDataConflictException("삭제 상태 또는 공고 기관이 원천과 다릅니다. 공고 관리에서 확인해 주세요.");
        }
    }

    /** 정정·취소 공고가 이어 붙을 이전 공고. 보정은 이전 공고를 대신 등록하지 않는다. */
    Announcement previousOf(ResolvedAnnouncement current) {
        String previousIdentifier = current.data().previousSourceAnnouncementIdentifier();
        if (previousIdentifier == null) {
            return null;
        }
        return announcements.findBySourceAnnouncementIdentifier(previousIdentifier)
                .orElseThrow(() -> new InvalidIngestRequestException("이전 공고를 먼저 등록해 주세요."));
    }

    /** 저장 매칭 해제와 공개 제외처럼 정제 없이 바꾸는 보정이 잠글 공고. */
    Optional<Announcement> locked(String identifier) {
        return announcements.findBySourceAnnouncementIdentifierForUpdate(identifier);
    }

    /** 보정을 쓴 뒤 이력을 남길 공고. 쓰기가 공고를 만들었으므로 반드시 있다. */
    Announcement written(String identifier) {
        return announcements.findBySourceAnnouncementIdentifier(identifier).orElseThrow();
    }
}
