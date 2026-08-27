package com.toadzip.backend.ingest.service;

import static com.toadzip.backend.ingest.exception.exception.RoadAddressGeocodingFailureReason.ADDRESS_NOT_FOUND;
import static com.toadzip.backend.ingest.exception.exception.RoadAddressGeocodingFailureReason.AMBIGUOUS_ADDRESS;
import static com.toadzip.backend.ingest.exception.exception.RoadAddressGeocodingFailureReason.COORDINATE_NOT_FOUND;
import static com.toadzip.backend.ingest.exception.exception.RoadAddressGeocodingFailureReason.INVALID_ADDRESS;

import com.toadzip.backend.ingest.domain.NormalizedRoadAddress;
import com.toadzip.backend.ingest.domain.RoadAddressCandidate;
import com.toadzip.backend.ingest.domain.UtmKCoordinate;
import com.toadzip.backend.ingest.domain.Wgs84Coordinate;
import com.toadzip.backend.ingest.dto.GeocodedRoadAddress;
import com.toadzip.backend.ingest.exception.exception.RoadAddressGeocodingException;
import com.toadzip.backend.ingest.repository.RoadAddressCoordinateRepository;
import com.toadzip.backend.ingest.repository.external.JusoApiException;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Service;

@Service
public class RoadAddressGeocodingService {

    private final RoadAddressCoordinateRepository repository;

    private final UtmKCoordinateConverter coordinateConverter;

    private final ConcurrentMap<String, GeocodedRoadAddress> cache = new ConcurrentHashMap<>();

    public RoadAddressGeocodingService(
            RoadAddressCoordinateRepository repository,
            UtmKCoordinateConverter coordinateConverter
    ) {
        this.repository = repository;
        this.coordinateConverter = coordinateConverter;
    }

    public GeocodedRoadAddress geocode(String rawRoadAddress) {
        NormalizedRoadAddress address = normalize(rawRoadAddress);
        return cache.computeIfAbsent(address.withoutReference(), ignored -> geocode(address));
    }

    private GeocodedRoadAddress geocode(NormalizedRoadAddress address) {
        try {
            RoadAddressCandidate candidate = selectCandidate(address, repository.search(address.withoutReference()));
            UtmKCoordinate utmKCoordinate = repository.findCoordinate(candidate.addressCode())
                    .orElseThrow(() -> new RoadAddressGeocodingException(
                            COORDINATE_NOT_FOUND,
                            "확정된 도로명주소의 좌표가 제공되지 않습니다."
                    ));
            Wgs84Coordinate wgs84Coordinate = coordinateConverter.convert(utmKCoordinate);
            return GeocodedRoadAddress.of(candidate.roadAddressWithoutReference(), wgs84Coordinate);
        }
        catch (JusoApiException exception) {
            throw new RoadAddressGeocodingException(
                    exception.getReason(),
                    exception.getMessage(),
                    exception
            );
        }
    }

    private RoadAddressCandidate selectCandidate(
            NormalizedRoadAddress source,
            List<RoadAddressCandidate> candidates
    ) {
        List<RoadAddressCandidate> matches = candidates.stream()
                .filter(candidate -> candidate.matches(source))
                .toList();
        if (matches.isEmpty()) {
            throw new RoadAddressGeocodingException(
                    ADDRESS_NOT_FOUND,
                    "원본과 일치하는 도로명주소를 찾지 못했습니다."
            );
        }
        if (matches.size() > 1) {
            throw new RoadAddressGeocodingException(
                    AMBIGUOUS_ADDRESS,
                    "원본과 일치하는 도로명주소가 여러 건입니다."
            );
        }
        return matches.getFirst();
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
