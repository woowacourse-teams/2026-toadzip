package com.toadzip.backend.announcement.domain;

import static jakarta.persistence.FetchType.LAZY;
import static lombok.AccessLevel.PROTECTED;

import com.toadzip.backend.housing.domain.HousingComplex;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import java.io.Serializable;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.Subselect;
import org.hibernate.annotations.Synchronize;

/**
 * 공고와 단지의 연결 하나. 공고↔단지 연결은 공고 단지(announcement_complexes)로만 정하고, 그 규칙은 {@link #LINKS} 한 곳에 둔다.
 * 공개 화면(지도·검색·알림·단지 상세 공고 목록)과 관리 화면이 이 규칙으로 공고의 단지를 찾는다. 읽기 전용이다.
 */
@Getter
@Entity
@Immutable
@Subselect(AnnouncementHousingComplex.LINKS)
@Synchronize({"announcement_complexes", "announcements", "supply_rows", "agency_supply_lines"})
@IdClass(AnnouncementHousingComplex.Key.class)
@NoArgsConstructor(access = PROTECTED)
public class AnnouncementHousingComplex {

    /**
     * (announcement_id, housing_complex_id) 연결 집합. 단지에 이어진 공고 단지가 연결이다. 다만 공고 공급표(정본)를 확정한 공고는 그 공고
     * 단지에 제외하지 않은 공급행이 있을 때만 연결로 본다(관리자가 한 단지의 행을 모두 제외하거나 지우면 그 단지가 빠진다). 확정 전 공고는
     * 원천에 아직 있는 공고 단지이고, 공고에 기관 준비 줄이 있으면 그 공고 단지를 가리키는 준비 줄이 있을 때만이다(기관이 정정으로 뺀
     * 단지와 공급 줄이 없는 단지는 빠진다). 준비 줄이 없는 공고(SH)는 원천에 있는 공고 단지 전부다. 이 판단은 여기에만 둔다. 한 공고가 같은 단지를 여러 공고 단지로 가리켜도 한 번만 낸다.
     */
    public static final String LINKS = """
            SELECT DISTINCT link.announcement_id, link.housing_complex_id
            FROM announcement_complexes link
            JOIN announcements link_announcement ON link_announcement.id = link.announcement_id
            WHERE link.housing_complex_id IS NOT NULL
              AND CASE WHEN link_announcement.supply_confirmed_at IS NULL THEN link.present_in_source
                       AND (EXISTS (SELECT 1 FROM agency_supply_lines link_line WHERE link_line.announcement_complex_id = link.id)
                            OR NOT EXISTS (SELECT 1 FROM agency_supply_lines link_line
                                WHERE link_line.announcement_id = link.announcement_id))
                   ELSE EXISTS (SELECT 1 FROM supply_rows link_row
                       WHERE link_row.announcement_complex_id = link.id AND link_row.admin_excluded = false) END
            """;

    @Id
    @Column(name = "announcement_id")
    private Long announcementId;

    @Id
    @Column(name = "housing_complex_id")
    private Long housingComplexId;

    @ManyToOne(fetch = LAZY)
    @JoinColumn(name = "announcement_id", insertable = false, updatable = false)
    private Announcement announcement;

    @ManyToOne(fetch = LAZY)
    @JoinColumn(name = "housing_complex_id", insertable = false, updatable = false)
    private HousingComplex housingComplex;

    @EqualsAndHashCode
    @NoArgsConstructor
    public static class Key implements Serializable {
        private Long announcementId;
        private Long housingComplexId;
    }
}
