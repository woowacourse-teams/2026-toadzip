package com.toadzip.backend.ingest.location.domain;

public enum RoadAddressGeocodingFailureReason {
    INVALID_ADDRESS,
    ADDRESS_NOT_FOUND,
    COORDINATE_NOT_FOUND,
    COORDINATE_CONVERSION_ERROR
}
