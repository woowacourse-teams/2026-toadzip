package com.toadzip.backend.eligibility.service;

import com.toadzip.backend.eligibility.domain.EligibilityContent;
import com.toadzip.backend.eligibility.domain.EligibilityContentValidator;
import com.toadzip.backend.eligibility.domain.IncomeStandard;
import com.toadzip.backend.eligibility.domain.StoredEligibilityRuleSet;
import com.toadzip.backend.eligibility.dto.EligibilityRequests.DraftVersion;
import com.toadzip.backend.eligibility.dto.EligibilityRequests.SaveDraft;
import com.toadzip.backend.eligibility.dto.EligibilityResponses.RuleSet;
import com.toadzip.backend.eligibility.exception.EligibilityConflictException;
import com.toadzip.backend.eligibility.exception.EligibilityNotFoundException;
import com.toadzip.backend.eligibility.repository.EligibilityAnnouncementRepository;
import com.toadzip.backend.eligibility.repository.EligibilityJsonCodec;
import com.toadzip.backend.eligibility.repository.EligibilityReferenceRepository;
import com.toadzip.backend.eligibility.repository.EligibilityRegionMembership;
import com.toadzip.backend.eligibility.repository.EligibilityRuleSetRepository;
import com.toadzip.backend.eligibility.repository.EligibilityStructureRepository;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Service
@PreAuthorize("hasRole('ADMIN')")
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class EligibilityRuleSetService {
    private final EligibilityAnnouncementRepository announcements;
    private final EligibilityRuleSetRepository ruleSets;
    private final EligibilityStructureRepository structures;
    private final EligibilityReferenceRepository references;
    private final EligibilityRegionMembership regions;
    private final EligibilityJsonCodec codec;
    private final Clock clock;

    public EligibilityRuleSetService(
            EligibilityAnnouncementRepository announcements,
            EligibilityRuleSetRepository ruleSets,
            EligibilityStructureRepository structures,
            EligibilityReferenceRepository references,
            EligibilityRegionMembership regions,
            EligibilityJsonCodec codec,
            Clock clock) {
        this.announcements = announcements;
        this.ruleSets = ruleSets;
        this.structures = structures;
        this.references = references;
        this.regions = regions;
        this.codec = codec;
        this.clock = clock;
    }

    public List<IncomeStandard> incomeStandards() {
        return references.incomeStandards().values().stream()
                .sorted(Comparator.comparingLong(IncomeStandard::id))
                .toList();
    }

    public RuleSet draft(long announcementId) {
        announcements.latest(announcementId);
        return ruleSets.find(announcementId, "DRAFT").map(this::view).orElse(null);
    }

    /** 지금 사용자에게 보이는 공개본. 없으면 null이다. */
    public RuleSet published(long announcementId) {
        announcements.latest(announcementId);
        return ruleSets.find(announcementId, "PUBLISHED").map(this::view).orElse(null);
    }

    /** 신청자격을 공개할 수 있는 공고인지(최신 게시물이고 공개 상태이며 삭제·취소되지 않음). 공개는 이 조건을 잠근 뒤 다시 확인한다. */
    public boolean publishable(long announcementId) {
        return publishable(announcementId, announcements.latest(announcementId));
    }

    @Transactional
    public RuleSet save(long announcementId, SaveDraft request) {
        String encoded = codec.write(request.content());
        validator().validate(request.content(), false);
        announcements.lockAncestors(announcementId);
        Optional<StoredEligibilityRuleSet> current = ruleSets.lockDraft(announcementId);
        if (request.expectedRevision() == 0
                && request.expectedDraftId() == null
                && current.isEmpty()) {
            return storeStructure(ruleSets.insertDraft(announcementId, encoded, clock.instant()));
        }
        StoredEligibilityRuleSet draft =
                requireVersion(
                        current,
                        new DraftVersion(request.expectedDraftId(), request.expectedRevision()));
        return storeStructure(ruleSets.updateDraft(draft.id(), encoded, clock.instant()));
    }

    @Transactional
    public RuleSet copy(long announcementId) {
        announcements.lockAncestors(announcementId);
        if (ruleSets.lockDraft(announcementId).isPresent()) {
            throw new EligibilityConflictException();
        }
        StoredEligibilityRuleSet published =
                ruleSets.find(announcementId, "PUBLISHED")
                        .orElseThrow(EligibilityNotFoundException::new);
        return storeStructure(ruleSets.insertDraft(announcementId, published.content(), clock.instant()));
    }

    public void validate(long announcementId, DraftVersion request) {
        StoredEligibilityRuleSet draft = requireVersion(ruleSets.find(announcementId, "DRAFT"), request);
        validator().validate(content(draft), true);
    }

    @Transactional
    public RuleSet publish(long announcementId, DraftVersion request) {
        List<Long> lineage = announcements.lockAncestors(announcementId);
        if (!publishable(announcementId, announcements.latest(announcementId))) {
            throw new EligibilityConflictException();
        }
        StoredEligibilityRuleSet draft = requireVersion(ruleSets.lockDraft(announcementId), request);
        EligibilityContent content = content(draft);
        validator().validate(content, true);
        long version = Math.addExact(ruleSets.currentVersion(announcementId), 1);
        ruleSets.deletePublished(lineage);
        return RuleSet.of(ruleSets.publish(draft.id(), version, clock.instant()), content);
    }

    private static boolean publishable(long announcementId, EligibilityAnnouncementRepository.Current latest) {
        return latest.id() == announcementId
                && !latest.deleted()
                && "PUBLISHED".equals(latest.releaseStatus())
                && !latest.cancelled();
    }

    private StoredEligibilityRuleSet requireVersion(
            Optional<StoredEligibilityRuleSet> current, DraftVersion request) {
        if (current.isEmpty()
                || request.draftId() == null
                || current.get().id() != request.draftId()
                || request.expectedRevision() == null
                || current.get().revision() != request.expectedRevision()) {
            throw new EligibilityConflictException();
        }
        return current.get();
    }

    private EligibilityContentValidator validator() {
        return new EligibilityContentValidator(regions, references.incomeStandards().keySet());
    }

    private EligibilityContent content(StoredEligibilityRuleSet stored) {
        return codec.content(stored.content(), stored.schemaVersion());
    }

    private RuleSet storeStructure(StoredEligibilityRuleSet stored) {
        structures.replace(stored.id());
        return view(stored);
    }

    private RuleSet view(StoredEligibilityRuleSet stored) {
        return RuleSet.of(stored, content(stored));
    }
}
