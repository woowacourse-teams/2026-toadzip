package com.toadzip.backend.housing.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.toadzip.backend.housing.domain.HousingComplex;

public interface HousingComplexRepository extends JpaRepository<HousingComplex, Long> {
}
