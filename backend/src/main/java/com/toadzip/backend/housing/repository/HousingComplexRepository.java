package com.toadzip.backend.housing.repository;

import com.toadzip.backend.housing.domain.HousingComplex;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface HousingComplexRepository extends JpaRepository<HousingComplex, Long> {

    Optional<HousingComplex> findBySourceComplexIdentifier(String sourceComplexIdentifier);

    @Query("""
            SELECT complex
            FROM HousingComplex complex
            WHERE complex.address.pnu = :pnu
              AND complex.supplyType = :supplyType
            """)
    List<HousingComplex> findAllByPnuAndSupplyType(
            @Param("pnu") String pnu,
            @Param("supplyType") String supplyType
    );
}
