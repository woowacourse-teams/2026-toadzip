package com.toadzip.backend.announcement.repository;

import com.toadzip.backend.announcement.domain.AnnouncementHousingComplex;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.repository.Repository;

/** 공고↔단지 연결({@link AnnouncementHousingComplex#LINKS}) 읽기. */
public interface AnnouncementHousingComplexRepository
        extends Repository<AnnouncementHousingComplex, AnnouncementHousingComplex.Key> {

    @EntityGraph(attributePaths = "housingComplex")
    List<AnnouncementHousingComplex> findAllByAnnouncementIdInOrderByAnnouncementIdAscHousingComplexIdAsc(
            Collection<Long> announcementIds);

    default List<AnnouncementHousingComplex> findAllByAnnouncementId(long announcementId) {
        return findAllByAnnouncementIdInOrderByAnnouncementIdAscHousingComplexIdAsc(List.of(announcementId));
    }

    @EntityGraph(attributePaths = "announcement")
    List<AnnouncementHousingComplex> findAllByHousingComplexIdOrderByAnnouncementIdAsc(long housingComplexId);
}
