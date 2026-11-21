package com.toadzip.backend.announcement.repository;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.AnnouncementComplex;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AnnouncementComplexRepository extends JpaRepository<AnnouncementComplex, Long> {

    List<AnnouncementComplex> findAllByAnnouncementOrderByIdAsc(Announcement announcement);

    /** 휴지통이 아닌 공고에서 이 단지에 이어진 공고 단지. 단지 이름을 공고 단지명으로 맞출 때 공고마다 이름이 다른지 본다. */
    List<AnnouncementComplex> findAllByHousingComplexIdAndAnnouncementAdminDeletedFalse(long housingComplexId);

    /** 휴지통에 없는 공고의 공고 단지가 이 단지를 가리키는지. 공개 연결 규칙과 달리 제외 행만 남은 공고 단지도 센다. */
    boolean existsByHousingComplexIdAndAnnouncementAdminDeletedFalse(long housingComplexId);

    /** 이 공고의 공고 단지가 휴지통 단지를 가리키는지. 공개 연결 규칙과 달리 제외 행만 남은 공고 단지도 센다. */
    boolean existsByAnnouncementIdAndHousingComplexAdminDeletedTrue(long announcementId);
}
