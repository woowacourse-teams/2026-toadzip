package com.toadzip.backend.housing.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.toadzip.backend.housing.domain.Address;
import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.dto.request.AdminHousingComplexCreateRequest;
import com.toadzip.backend.housing.dto.request.AdminHousingComplexCreateRequest.AddressRequest;
import com.toadzip.backend.housing.dto.response.AdminHousingComplexCreateResponse;
import com.toadzip.backend.housing.repository.HousingComplexRepository;

@Service
public class AdminHousingComplexRegistrationService {

    private final HousingComplexRepository housingComplexRepository;

    private final HousingComplexSourceIdentifierGenerator identifierGenerator;

    public AdminHousingComplexRegistrationService(
            HousingComplexRepository housingComplexRepository,
            HousingComplexSourceIdentifierGenerator identifierGenerator
    ) {
        this.housingComplexRepository = housingComplexRepository;
        this.identifierGenerator = identifierGenerator;
    }

    @Transactional
    public AdminHousingComplexCreateResponse register(AdminHousingComplexCreateRequest request) {
        Address address = createAddress(request.address());
        HousingComplex housingComplex = housingComplexRepository.save(createHousingComplex(request, address));
        return new AdminHousingComplexCreateResponse(
                housingComplex.getId(),
                housingComplex.getName(),
                address.getRoadAddress()
        );
    }

    private HousingComplex createHousingComplex(
            AdminHousingComplexCreateRequest request,
            Address address
    ) {
        return HousingComplex.create(
                request.name(),
                identifierGenerator.generate(),
                request.rentalType().name(),
                address,
                request.totalHouseholdCount(),
                request.agencyCode().name(),
                request.completionDate(),
                request.heatingType().name(),
                request.buildingType().name(),
                request.corridorType().name(),
                request.hasElevator(),
                request.totalParkingCount(),
                request.overviewImageUrl(),
                request.moveOutCountLastYear()
        );
    }

    private Address createAddress(AddressRequest request) {
        return Address.create(
                request.roadAddress(),
                request.pnu(),
                request.legalDongCode(),
                request.provinceCode(),
                request.cityCountyDistrictCode(),
                request.latitude(),
                request.longitude()
        );
    }
}
