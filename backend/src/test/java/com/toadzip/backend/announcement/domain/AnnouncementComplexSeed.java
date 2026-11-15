package com.toadzip.backend.announcement.domain;

import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;

/**
 * DB 테스트에서 직접 만든 공급행을 등록·PDF 적용처럼 공고 단지에 넣는다. 공고↔단지 연결은 공고 단지로만 보이므로
 * ({@link AnnouncementHousingComplex#LINKS}) 행의 단지를 공개 화면에서 보려면 행이 단지에 이어진 공고 단지에 들어야 한다.
 */
public final class AnnouncementComplexSeed {

    private AnnouncementComplexSeed() {
    }

    /**
     * 행의 (공고, 단지명) 공고 단지를 찾거나 만들어 행을 넣는다. 공고 단지가 아직 단지에 이어지지 않았고 행이 단지를 가리키면 그
     * 단지로 관리자 연결한다. 이미 공고 단지에 든 행은 그대로 둔다.
     */
    public static SupplyRow place(EntityManager entities, SupplyRow row) {
        if (row.getAnnouncementComplex() != null) {
            return row;
        }
        AnnouncementComplex complex = find(entities, row);
        if (complex == null) {
            complex = AnnouncementComplex.observe(row.getAnnouncement(), row.getSourceComplexName().strip(), null,
                    null);
            entities.persist(complex);
        }
        if (complex.getHousingComplex() == null && row.getHousingComplex() != null) {
            complex.linkByAdmin(row.getHousingComplex(), "test", Instant.parse("2026-01-01T00:00:00Z"));
        }
        row.fillAnnouncementComplex(complex);
        return row;
    }

    private static AnnouncementComplex find(EntityManager entities, SupplyRow row) {
        List<AnnouncementComplex> stored = entities.createQuery("""
                SELECT complex FROM AnnouncementComplex complex
                WHERE complex.announcement = :announcement AND complex.sourceComplexName = :name
                """, AnnouncementComplex.class).setParameter("announcement", row.getAnnouncement())
                .setParameter("name", row.getSourceComplexName().strip()).getResultList();
        return stored.isEmpty() ? null : stored.getFirst();
    }
}
