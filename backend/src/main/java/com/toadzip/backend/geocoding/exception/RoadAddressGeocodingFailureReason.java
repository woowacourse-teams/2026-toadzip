package com.toadzip.backend.geocoding.exception;

public enum RoadAddressGeocodingFailureReason {
    INVALID_ADDRESS,
    ADDRESS_NOT_FOUND,
    AMBIGUOUS_ADDRESS,
    COORDINATE_NOT_FOUND,
    EXTERNAL_API_ERROR,
    COORDINATE_CONVERSION_ERROR,
    NOT_CONFIGURED
}
