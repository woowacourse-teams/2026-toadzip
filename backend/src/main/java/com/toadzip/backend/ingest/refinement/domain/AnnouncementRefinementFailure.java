package com.toadzip.backend.ingest.refinement.domain;

import static lombok.AccessLevel.PROTECTED;

import com.toadzip.backend.housing.domain.AgencyCode;
import com.toadzip.backend.ingest.failure.domain.IngestFailure;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * LH·SH·GH 공고 정제가 끝내지 못한 원천. 원천 키는 SH·GH는 공고 원천 식별자({@code SH:seq}, {@code GH:pbancNo})이고, LH는
 * 공고목록 원천 키({@code 연계시스템:상위유형:유형:공고ID})다. 정제할 원천을 읽지 못한 실패는 묶음 이름(예: {@code SH 페이지 123})을
 * 키로 쓴다.
 */
@Getter
@Entity
@Table(name = "announcement_refinement_failures")
@NoArgsConstructor(access = PROTECTED)
public class AnnouncementRefinementFailure extends IngestFailure<AnnouncementRefinementFailure> {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private AgencyCode provider;

    /** SH·GH 원천 정제 실패의 공고 원천 식별자. 정제가 만든 공고와 같은 값이다. */
    private String sourceAnnouncementIdentifier;

    /** LH 원천 정제 실패의 공고 ID. */
    private String panId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private AnnouncementRefinementFailureReason reason;

    private AnnouncementRefinementFailure(AgencyCode provider, String sourceKey,
            AnnouncementRefinementFailureReason reason, String detail, Instant occurredAt) {
        super(sourceKey, reason, detail, occurredAt);
        this.provider = provider;
        this.reason = reason;
        if (reason == AnnouncementRefinementFailureReason.SOURCE_REFINEMENT_FAILED && provider == AgencyCode.LH) {
            panId = sourceKey.substring(sourceKey.lastIndexOf(':') + 1);
        }
        else if (reason == AnnouncementRefinementFailureReason.SOURCE_REFINEMENT_FAILED) {
            sourceAnnouncementIdentifier = sourceKey;
        }
    }

    public static AnnouncementRefinementFailure observed(AgencyCode provider, String sourceKey,
            AnnouncementRefinementFailureReason reason, String detail, Instant occurredAt) {
        if (provider == null) {
            throw new IllegalArgumentException("공급기관은 필수입니다.");
        }
        return new AnnouncementRefinementFailure(provider, sourceKey, reason, detail, occurredAt);
    }

    @Override
    protected void updateObservedDetails(AnnouncementRefinementFailure observed) {
        sourceAnnouncementIdentifier = observed.sourceAnnouncementIdentifier;
        panId = observed.panId;
    }
}
