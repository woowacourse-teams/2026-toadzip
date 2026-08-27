package com.toadzip.backend.ingest.service;

import com.toadzip.backend.housing.domain.Address;
import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.domain.HousingType;
import com.toadzip.backend.housing.repository.HousingComplexRepository;
import com.toadzip.backend.housing.repository.HousingTypeRepository;
import com.toadzip.backend.ingest.dto.MyHomeComplexMappingReport;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MyHomeComplexMappingWriter {

    private final HousingComplexRepository complexRepository;

    private final HousingTypeRepository housingTypeRepository;

    public MyHomeComplexMappingWriter(
            HousingComplexRepository complexRepository,
            HousingTypeRepository housingTypeRepository
    ) {
        this.complexRepository = complexRepository;
        this.housingTypeRepository = housingTypeRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public MyHomeComplexMappingReport write(MyHomeComplexMappingData data, Address address) {
        ComplexWriteResult complexResult = upsertComplex(data, address);
        HousingTypeWriteResult housingTypeResult = synchronizeHousingTypes(
                complexResult.complex(),
                data.housingTypes()
        );
        return new MyHomeComplexMappingReport(
                complexResult.created() ? 1 : 0,
                complexResult.updated() ? 1 : 0,
                complexResult.unchanged() ? 1 : 0,
                housingTypeResult.created(),
                housingTypeResult.updated(),
                housingTypeResult.unchanged(),
                housingTypeResult.deleted(),
                0
        );
    }

    private ComplexWriteResult upsertComplex(MyHomeComplexMappingData data, Address address) {
        HousingComplex complex = complexRepository
                .findBySourceComplexIdentifier(data.sourceComplexIdentifier())
                .orElse(null);
        if (complex == null) {
            HousingComplex created = HousingComplex.createFromMyHome(
                    data.name(),
                    data.sourceComplexIdentifier(),
                    data.supplyType(),
                    address,
                    data.totalHouseholdCount(),
                    data.provider(),
                    data.completionDate(),
                    data.heatingType(),
                    data.housingType(),
                    data.corridorType(),
                    data.elevatorInstalled(),
                    data.parkingSpaceCount()
            );
            return new ComplexWriteResult(complexRepository.save(created), true, false);
        }
        boolean updated = complex.updateFromMyHome(
                data.name(),
                data.supplyType(),
                address,
                data.totalHouseholdCount(),
                data.provider(),
                data.completionDate(),
                data.heatingType(),
                data.housingType(),
                data.corridorType(),
                data.elevatorInstalled(),
                data.parkingSpaceCount()
        );
        return new ComplexWriteResult(complex, false, updated);
    }

    private HousingTypeWriteResult synchronizeHousingTypes(
            HousingComplex complex,
            List<MyHomeHousingTypeMappingData> incoming
    ) {
        Map<String, HousingType> storedByIdentifier = housingTypeRepository.findAllByHousingComplex(complex)
                .stream()
                .filter(type -> type.getSourceHousingTypeIdentifier() != null)
                .collect(Collectors.toMap(
                        HousingType::getSourceHousingTypeIdentifier,
                        Function.identity(),
                        (first, second) -> first,
                        LinkedHashMap::new
                ));
        int created = 0;
        int updated = 0;
        int unchanged = 0;
        for (MyHomeHousingTypeMappingData data : incoming) {
            HousingType housingType = storedByIdentifier.get(data.sourceHousingTypeIdentifier());
            if (housingType == null) {
                housingTypeRepository.save(HousingType.createFromMyHome(
                        complex,
                        data.sourceHousingTypeIdentifier(),
                        data.name(),
                        data.exclusiveArea(),
                        data.supplyArea()
                ));
                created++;
                continue;
            }
            if (housingType.updateFromMyHome(data.name(), data.exclusiveArea(), data.supplyArea())) {
                updated++;
                continue;
            }
            unchanged++;
        }
        Set<String> incomingIdentifiers = incoming.stream()
                .map(MyHomeHousingTypeMappingData::sourceHousingTypeIdentifier)
                .collect(Collectors.toSet());
        List<HousingType> stale = storedByIdentifier.entrySet()
                .stream()
                .filter(entry -> !incomingIdentifiers.contains(entry.getKey()))
                .map(Map.Entry::getValue)
                .toList();
        housingTypeRepository.deleteAll(stale);
        return new HousingTypeWriteResult(created, updated, unchanged, stale.size());
    }

    private record ComplexWriteResult(HousingComplex complex, boolean created, boolean updated) {

        boolean unchanged() {
            return !created && !updated;
        }
    }

    private record HousingTypeWriteResult(int created, int updated, int unchanged, int deleted) {
    }
}
