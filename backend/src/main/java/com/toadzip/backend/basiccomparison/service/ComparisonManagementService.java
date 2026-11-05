package com.toadzip.backend.basiccomparison.service;

import com.toadzip.backend.basiccomparison.domain.ComparisonContent;
import com.toadzip.backend.basiccomparison.domain.ComparisonContentValidator;
import com.toadzip.backend.basiccomparison.domain.IncomeStandard;
import com.toadzip.backend.basiccomparison.domain.StoredComparison;
import com.toadzip.backend.basiccomparison.dto.ComparisonRequests.DraftVersion;
import com.toadzip.backend.basiccomparison.dto.ComparisonRequests.SaveDraft;
import com.toadzip.backend.basiccomparison.dto.ComparisonResponses.RuleSet;
import com.toadzip.backend.basiccomparison.exception.ComparisonConflictException;
import com.toadzip.backend.basiccomparison.exception.ComparisonNotFoundException;
import com.toadzip.backend.basiccomparison.repository.ComparisonAnnouncementRepository;
import com.toadzip.backend.basiccomparison.repository.ComparisonJsonCodec;
import com.toadzip.backend.basiccomparison.repository.ComparisonReferenceRepository;
import com.toadzip.backend.basiccomparison.repository.ComparisonRegionMembership;
import com.toadzip.backend.basiccomparison.repository.ComparisonRuleSetRepository;

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
public class ComparisonManagementService {
    private final ComparisonAnnouncementRepository announcements;
    private final ComparisonRuleSetRepository ruleSets;
    private final ComparisonReferenceRepository references;
    private final ComparisonRegionMembership regions;
    private final ComparisonJsonCodec codec;
    private final Clock clock;

    public ComparisonManagementService(
            ComparisonAnnouncementRepository announcements,
            ComparisonRuleSetRepository ruleSets,
            ComparisonReferenceRepository references,
            ComparisonRegionMembership regions,
            ComparisonJsonCodec codec,
            Clock clock) {
        this.announcements = announcements;
        this.ruleSets = ruleSets;
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

    @Transactional
    public RuleSet save(long announcementId, SaveDraft request) {
        String encoded = codec.write(request.content());
        validator().validate(request.content(), false);
        announcements.lockAncestors(announcementId);
        Optional<StoredComparison> current = ruleSets.lockDraft(announcementId);
        if (request.expectedRevision() == 0
                && request.expectedDraftId() == null
                && current.isEmpty()) {
            return view(ruleSets.insertDraft(announcementId, encoded, clock.instant()));
        }
        StoredComparison draft =
                requireVersion(
                        current,
                        new DraftVersion(request.expectedDraftId(), request.expectedRevision()));
        return view(ruleSets.updateDraft(draft.id(), encoded, clock.instant()));
    }

    @Transactional
    public RuleSet copy(long announcementId) {
        announcements.lockAncestors(announcementId);
        if (ruleSets.lockDraft(announcementId).isPresent()) {
            throw new ComparisonConflictException();
        }
        StoredComparison published =
                ruleSets.find(announcementId, "PUBLISHED")
                        .orElseThrow(ComparisonNotFoundException::new);
        return view(ruleSets.insertDraft(announcementId, published.content(), clock.instant()));
    }

    public void validate(long announcementId, DraftVersion request) {
        StoredComparison draft = requireVersion(ruleSets.find(announcementId, "DRAFT"), request);
        validator().validate(content(draft), true);
    }

    @Transactional
    public RuleSet publish(long announcementId, DraftVersion request) {
        List<Long> lineage = announcements.lockAncestors(announcementId);
        var latest = announcements.latest(announcementId);
        if (latest.id() != announcementId
                || latest.deleted()
                || !"PUBLISHED".equals(latest.releaseStatus())
                || latest.cancelled()) {
            throw new ComparisonConflictException();
        }
        StoredComparison draft = requireVersion(ruleSets.lockDraft(announcementId), request);
        ComparisonContent content = content(draft);
        validator().validate(content, true);
        long version = Math.addExact(ruleSets.currentVersion(announcementId), 1);
        ruleSets.deletePublished(lineage);
        return RuleSet.of(ruleSets.publish(draft.id(), version, clock.instant()), content);
    }

    private StoredComparison requireVersion(
            Optional<StoredComparison> current, DraftVersion request) {
        if (current.isEmpty()
                || request.draftId() == null
                || current.get().id() != request.draftId()
                || request.expectedRevision() == null
                || current.get().revision() != request.expectedRevision()) {
            throw new ComparisonConflictException();
        }
        return current.get();
    }

    private ComparisonContentValidator validator() {
        return new ComparisonContentValidator(regions, references.incomeStandards().keySet());
    }

    private ComparisonContent content(StoredComparison stored) {
        return codec.content(stored.content(), stored.schemaVersion());
    }

    private RuleSet view(StoredComparison stored) {
        return RuleSet.of(stored, content(stored));
    }
}
