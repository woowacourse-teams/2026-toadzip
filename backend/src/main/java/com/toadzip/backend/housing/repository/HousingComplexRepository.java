package com.toadzip.backend.housing.repository;

import com.toadzip.backend.housing.domain.HousingComplex;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface HousingComplexRepository extends JpaRepository<HousingComplex, Long> {
    /**
     * 단지명은 공백·'-'와 블록 접미어(BL·블록·블럭)를 빼고도 비교한다. 공고문은 "동탄2 A63BL", 단지는 "화성동탄2 A63블록"처럼
     * 블록 표기가 달라서다. 키워드 패턴의 이스케이프(!)는 그대로 남는다.
     */
    String ADMIN_SEARCH_FROM = " FROM housing_complexes c " + ComplexVerificationSql.LATEST_REVIEW + """
            WHERE c.admin_deleted = :deleted
              AND (:review = false OR c.source_review_required = true)
              AND (lower(c.name) LIKE :keyword ESCAPE '!' OR lower(c.road_address) LIKE :keyword ESCAPE '!'
                  OR regexp_replace(lower(c.name), '[[:space:]-]', '', 'g') LIKE regexp_replace(
                      regexp_replace(:keyword, '([0-9])(bl|블록|블럭)($|[^a-z])', '\\1\\3', 'g'), '[[:space:]-]', '', 'g') ESCAPE '!'
                  OR lower(c.source_complex_identifier) = :identifier)
              AND (:provider = '' OR c.provider IN (:provider, :providerLegacy))
              AND (:rental = '' OR c.supply_type IN (:rental, :rentalLegacy))
              AND (:region = '' OR c.province_code = :region OR c.city_county_district_code = :region)
            """ + " AND (:verification = '' OR (" + ComplexVerificationSql.STATUS + ") = :verification)";

    @Query(value = "SELECT c.*" + ADMIN_SEARCH_FROM + " ORDER BY c.created_at DESC NULLS LAST, c.id DESC",
            countQuery = "SELECT count(*)" + ADMIN_SEARCH_FROM, nativeQuery = true)
    org.springframework.data.domain.Page<HousingComplex> searchAdmin(String keyword, String identifier, String provider, String providerLegacy,
            String rental, String rentalLegacy, String region, boolean deleted, boolean review, String verification,
            org.springframework.data.domain.Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT complex FROM HousingComplex complex WHERE complex.id = :id")
    Optional<HousingComplex> findByIdForUpdate(@Param("id") long id);

    Optional<HousingComplex> findBySourceComplexIdentifier(String sourceComplexIdentifier);

    @Query("""
            SELECT complex
            FROM HousingComplex complex
            WHERE complex.adminDeleted = false AND complex.address.pnu = :pnu
              AND complex.supplyType = :supplyType
            """)
    List<HousingComplex> findAllByPnuAndSupplyType(
            @Param("pnu") String pnu,
            @Param("supplyType") String supplyType
    );

    @Query("""
            SELECT complex
            FROM HousingComplex complex
            WHERE complex.adminDeleted = false AND complex.address.pnu = :pnu
            ORDER BY complex.totalHouseholdCount DESC, complex.id
            """)
    List<HousingComplex> findAllByPnu(@Param("pnu") String pnu);
}
