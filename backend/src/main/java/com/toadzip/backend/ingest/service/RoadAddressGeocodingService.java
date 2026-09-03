package com.toadzip.backend.ingest.service;

import static com.toadzip.backend.ingest.exception.exception.RoadAddressGeocodingFailureReason.ADDRESS_NOT_FOUND;
import static com.toadzip.backend.ingest.exception.exception.RoadAddressGeocodingFailureReason.COORDINATE_NOT_FOUND;
import static com.toadzip.backend.ingest.exception.exception.RoadAddressGeocodingFailureReason.INVALID_ADDRESS;

import com.toadzip.backend.ingest.domain.NormalizedRoadAddress;
import com.toadzip.backend.ingest.domain.RoadAddressLocation;
import com.toadzip.backend.ingest.dto.GeocodedRoadAddress;
import com.toadzip.backend.ingest.exception.exception.RoadAddressGeocodingException;
import com.toadzip.backend.ingest.repository.RoadAddressLocationRepository;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class RoadAddressGeocodingService {

    private final RoadAddressLocationRepository locationRepository;

    private final UtmKCoordinateConverter coordinateConverter;

    public RoadAddressGeocodingService(
            RoadAddressLocationRepository locationRepository,
            UtmKCoordinateConverter coordinateConverter
    ) {
        this.locationRepository = locationRepository;
        this.coordinateConverter = coordinateConverter;
    }

    public GeocodedRoadAddress geocode(String rawRoadAddress) {
        NormalizedRoadAddress address = normalize(rawRoadAddress);
        List<RoadAddressLocation> locations = locationRepository
                .findAllByNormalizedRoadAddressOrderByIdEntranceSerialAsc(address.withoutReference());
        if (locations.isEmpty()) {
            throw new RoadAddressGeocodingException(
                    ADDRESS_NOT_FOUND,
                    "선별 적재된 위치정보요약DB에서 도로명주소를 찾지 못했습니다."
            );
        }
        RoadAddressLocation resolved = locations.stream()
                .filter(location -> location.coordinate().isPresent())
                .findFirst()
                .orElseThrow(() -> new RoadAddressGeocodingException(
                        COORDINATE_NOT_FOUND,
                        "위치정보요약DB에 해당 도로명주소의 좌표가 제공되지 않습니다."
                ));
        return GeocodedRoadAddress.of(
                resolved.getRoadAddress(),
                coordinateConverter.convert(resolved.coordinate().orElseThrow())
        );
    }

    private NormalizedRoadAddress normalize(String rawRoadAddress) {
        try {
            return new NormalizedRoadAddress(rawRoadAddress);
        }
        catch (IllegalArgumentException exception) {
            throw new RoadAddressGeocodingException(
                    INVALID_ADDRESS,
                    "도로명주소가 비어 있거나 형식이 올바르지 않습니다.",
                    exception
            );
        }
    }
}
