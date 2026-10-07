package com.toadzip.backend.streetview.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.toadzip.backend.housing.repository.ComplexDetailQueryRepository;
import com.toadzip.backend.housing.repository.ComplexDetailRow;
import com.toadzip.backend.streetview.domain.StreetViewPolicy;
import com.toadzip.backend.streetview.exception.StreetViewPolicyUnavailableException;
import com.toadzip.backend.streetview.repository.StreetViewPolicyRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class StreetViewQueryServiceTest {
    private final ComplexDetailQueryRepository complexes = mock(ComplexDetailQueryRepository.class);
    private final StreetViewPolicyRepository policies = mock(StreetViewPolicyRepository.class);
    private final StreetViewQueryService service = new StreetViewQueryService(complexes, policies);

    @ParameterizedTest
    @CsvSource(value = {"NULL,126.9", "37.5,NULL", "NULL,NULL"}, nullValues = "NULL")
    void 좌표가_누락되면_실행을_허용하지_않는다(BigDecimal latitude, BigDecimal longitude) {
        when(complexes.findComplex(1)).thenReturn(Optional.of(row(latitude, longitude)));
        var policy = StreetViewPolicy.initialize(Instant.EPOCH);
        policy.revise(true, "검증", "operator", Instant.EPOCH);
        when(policies.findById(1L)).thenReturn(Optional.of(policy));
        var response = service.get(1);
        assertThat(response.enabled()).isFalse();
        assertThat(response.disabledReason()).isEqualTo("INVALID_COORDINATES");
        assertThat(response.initialization()).isNull();
    }

    @Test
    void 정책이_없으면_공개_조회도_실패한다() {
        when(complexes.findComplex(1)).thenReturn(Optional.of(row(BigDecimal.ONE, BigDecimal.ONE)));
        when(policies.findById(1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get(1)).isInstanceOf(StreetViewPolicyUnavailableException.class);
    }

    private ComplexDetailRow row(BigDecimal latitude, BigDecimal longitude) {
        return new ComplexDetailRow(1, "단지", null, null, null, null, null, null, latitude, longitude,
                null, null, null, null, null, null, 0, 0, null, null, null, null);
    }
}
