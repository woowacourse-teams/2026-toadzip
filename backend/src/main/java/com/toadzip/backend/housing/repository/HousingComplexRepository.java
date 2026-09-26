package com.toadzip.backend.housing.repository;

import com.toadzip.backend.housing.domain.HousingComplex;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface HousingComplexRepository extends JpaRepository<HousingComplex, Long> {
    @Query(value = """
            SELECT c.* FROM housing_complexes c
            WHERE c.admin_deleted = :deleted
              AND (:review = false OR c.source_review_required = true)
              AND (lower(c.name) LIKE :keyword ESCAPE '!' OR lower(c.road_address) LIKE :keyword ESCAPE '!'
                  OR lower(c.source_complex_identifier) = :identifier)
              AND (:provider = '' OR c.provider IN (:provider, :providerLegacy))
              AND (:rental = '' OR c.supply_type IN (:rental, :rentalLegacy))
              AND (:region = '' OR c.province_code = :region OR c.city_county_district_code = :region)
            ORDER BY c.created_at DESC NULLS LAST, c.id DESC
            """, nativeQuery = true)
    org.springframework.data.domain.Page<HousingComplex> searchAdmin(String keyword, String identifier, String provider, String providerLegacy,
            String rental, String rentalLegacy, String region, boolean deleted, boolean review,
            org.springframework.data.domain.Pageable pageable);


    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select complex from HousingComplex complex where complex.id = :id")
    Optional<HousingComplex> findByIdForUpdate(long id);

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
}
