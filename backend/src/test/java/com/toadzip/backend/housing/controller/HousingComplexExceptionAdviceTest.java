package com.toadzip.backend.housing.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

import com.toadzip.backend.housing.exception.HousingComplexNotFoundException;
import com.toadzip.backend.housing.exception.InvalidComplexCursorException;
import com.toadzip.backend.housing.exception.InvalidComplexRequestException;
import com.toadzip.backend.housing.exception.InvalidMapBoundsException;
import com.toadzip.backend.housing.exception.InvalidRegionCodeException;

class HousingComplexExceptionAdviceTest {

    private final HousingComplexExceptionAdvice advice = new HousingComplexExceptionAdvice();

    @Test
    void 단지_조회_기능_예외를_고정된_오류_계약으로_변환한다() {
        assertError(
                advice.handleInvalidMapBounds(new InvalidMapBoundsException(), new MockHttpServletRequest()),
                BAD_REQUEST,
                "INVALID_MAP_BOUNDS"
        );
        assertError(
                advice.handleInvalidComplexCursor(new InvalidComplexCursorException(), new MockHttpServletRequest()),
                BAD_REQUEST,
                "INVALID_CURSOR"
        );
        assertError(
                advice.handleInvalidComplexRequest(new InvalidComplexRequestException(), new MockHttpServletRequest()),
                BAD_REQUEST,
                "INVALID_REQUEST"
        );
        assertError(
                advice.handleInvalidRegionCode(new InvalidRegionCodeException(), new MockHttpServletRequest()),
                BAD_REQUEST,
                "INVALID_REGION_CODE"
        );
        assertError(
                advice.handleHousingComplexNotFound(new HousingComplexNotFoundException(), new MockHttpServletRequest()),
                NOT_FOUND,
                "COMPLEX_NOT_FOUND"
        );
    }

    private void assertError(
            org.springframework.http.ResponseEntity<com.toadzip.backend.global.exception.ErrorResponse> response,
            HttpStatus status,
            String code
    ) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(code);
        assertThat(response.getBody().traceId()).isNotBlank();
        assertThat(response.getBody().errors()).isEmpty();
    }
}
