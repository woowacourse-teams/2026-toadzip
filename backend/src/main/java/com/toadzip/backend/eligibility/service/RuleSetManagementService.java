package com.toadzip.backend.eligibility.service;

import com.toadzip.backend.eligibility.domain.RuleSetContent;
import com.toadzip.backend.eligibility.domain.RuleSetValidator;
import com.toadzip.backend.eligibility.domain.StoredRuleSet;
import com.toadzip.backend.eligibility.dto.EligibilityRequests.DraftVersion;
import com.toadzip.backend.eligibility.dto.EligibilityRequests.SaveDraft;
import com.toadzip.backend.eligibility.dto.EligibilityResponses.RuleSet;
import com.toadzip.backend.eligibility.exception.EligibilityConflictException;
import com.toadzip.backend.eligibility.exception.EligibilityNotFoundException;
import com.toadzip.backend.eligibility.exception.InvalidEligibilityException;
import com.toadzip.backend.eligibility.repository.CatalogRegionMembership;
import com.toadzip.backend.eligibility.repository.EligibilityAnnouncementRepository;
import com.toadzip.backend.eligibility.repository.EligibilityReferenceRepository;
import com.toadzip.backend.eligibility.repository.RuleSetContentRepository;
import com.toadzip.backend.eligibility.repository.RuleSetJsonCodec;
import com.toadzip.backend.eligibility.repository.RuleSetRepository;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

@Service
@PreAuthorize("hasRole('ADMIN')")
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class RuleSetManagementService {
    private final EligibilityAnnouncementRepository announcements;
    private final RuleSetRepository ruleSets;
    private final RuleSetContentRepository contents;
    private final EligibilityReferenceRepository references;
    private final CatalogRegionMembership regions;
    private final RuleSetJsonCodec codec;
    private final Clock clock;

    public RuleSetManagementService(
            EligibilityAnnouncementRepository announcements,
            RuleSetRepository ruleSets,
            RuleSetContentRepository contents,
            EligibilityReferenceRepository references,
            CatalogRegionMembership regions,
            RuleSetJsonCodec codec,
            Clock clock) {
        this.announcements = announcements;
        this.ruleSets = ruleSets;
        this.contents = contents;
        this.references = references;
        this.regions = regions;
        this.codec = codec;
        this.clock = clock;
    }

    public RuleSet draft(long announcementId) {
        announcements.latest(announcementId);
        return ruleSets.find(announcementId, "DRAFT").map(this::view).orElse(null);
    }

    @Transactional
    public RuleSet save(long announcementId, SaveDraft request) {
        codec.snapshot(request.content());
        validator().validateStructure(request.content());
        announcements.lockAncestors(announcementId);
        Optional<StoredRuleSet> current = ruleSets.lockDraft(announcementId);
        StoredRuleSet saved = saveRoot(announcementId, current, request);
        replaceContent(saved.id(), request.content());
        return view(saved);
    }

    @Transactional
    public RuleSet copy(long announcementId) {
        announcements.lockAncestors(announcementId);
        if (ruleSets.lockDraft(announcementId).isPresent()) {
            throw new EligibilityConflictException();
        }
        StoredRuleSet published =
                ruleSets.find(announcementId, "PUBLISHED")
                        .orElseThrow(EligibilityNotFoundException::new);
        RuleSetContent content = contents.read(published.id(), published.metadata());
        StoredRuleSet draft =
                ruleSets.insertDraft(announcementId, content.metadata(), clock.instant());
        replaceContent(draft.id(), content);
        return view(draft);
    }

    public void validate(long announcementId, DraftVersion request) {
        StoredRuleSet draft = requireVersion(ruleSets.find(announcementId, "DRAFT"), request);
        validator().validateForPublication(contents.read(draft.id(), draft.metadata()));
    }

    @Transactional
    public RuleSet publish(long announcementId, DraftVersion request) {
        List<Long> lineage = announcements.lockAncestors(announcementId);
        var latest = announcements.latest(announcementId);
        if (latest.id() != announcementId
                || latest.deleted()
                || !"PUBLISHED".equals(latest.releaseStatus())
                || "취소공고".equals(latest.publicationType())) {
            throw new EligibilityConflictException();
        }
        StoredRuleSet draft = requireVersion(ruleSets.lockDraft(announcementId), request);
        RuleSetContent content = contents.read(draft.id(), draft.metadata());
        validator().validateForPublication(content);
        String snapshot = codec.snapshot(content);
        long nextVersion = Math.addExact(ruleSets.currentVersion(announcementId), 1);
        ruleSets.deletePublished(lineage);
        StoredRuleSet published =
                ruleSets.publish(draft.id(), nextVersion, snapshot, clock.instant());
        return RuleSet.of(published, content);
    }

    private StoredRuleSet saveRoot(
            long announcementId, Optional<StoredRuleSet> current, SaveDraft request) {
        if (request.expectedRevision() == 0
                && request.expectedDraftId() == null
                && current.isEmpty()) {
            return ruleSets.insertDraft(
                    announcementId, request.content().metadata(), clock.instant());
        }
        StoredRuleSet draft =
                requireVersion(
                        current,
                        new DraftVersion(request.expectedDraftId(), request.expectedRevision()));
        return ruleSets.updateDraft(draft.id(), request.content().metadata(), clock.instant());
    }

    private StoredRuleSet requireVersion(Optional<StoredRuleSet> current, DraftVersion request) {
        if (current.isEmpty()
                || request.draftId() == null
                || current.get().id() != request.draftId()
                || current.get().revision() != request.expectedRevision()) {
            throw new EligibilityConflictException();
        }
        return current.get();
    }

    private void replaceContent(long id, RuleSetContent content) {
        try {
            contents.replace(id, content);
        } catch (DataIntegrityViolationException exception) {
            // Expected constraints from admin-provided rows. Do not return SQL or the submitted
            // data.
            throw new InvalidEligibilityException("구조화 행의 값, 중복 또는 연결 관계를 확인해 주세요.");
        }
    }

    private RuleSetValidator validator() {
        return new RuleSetValidator(regions, references.incomeStandards().keySet());
    }

    private RuleSet view(StoredRuleSet stored) {
        return RuleSet.of(stored, contents.read(stored.id(), stored.metadata()));
    }
}
