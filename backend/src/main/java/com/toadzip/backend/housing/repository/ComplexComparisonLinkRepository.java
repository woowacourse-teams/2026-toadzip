package com.toadzip.backend.housing.repository;

import com.toadzip.backend.housing.domain.ComplexComparisonLink;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ComplexComparisonLinkRepository extends JpaRepository<ComplexComparisonLink, Long> {
    List<ComplexComparisonLink> findAllByHousingComplexIdOrderById(long housingComplexId);
}
