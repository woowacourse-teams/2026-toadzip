package com.toadzip.backend.housing.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.toadzip.backend.housing.domain.MapClusteringStage;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;

class CsvMapClusteringZoomPolicyRepositoryTest {

    private static final String HEADER = "policyVersion,regionDatasetVersion,fromStage,toStage,"
            + "boundaryZoom,hysteresis,expansionZoom";

    @Test
    void CSV에서_버전과_단계_전환_정책을_읽는다() {
        CsvMapClusteringZoomPolicyRepository repository = repository(validCsv());

        assertEquals("2026-09-02-v1", repository.current().policyVersion());
        assertEquals("2026-07-01", repository.current().regionDatasetVersion());
        assertEquals(
                MapClusteringStage.INDIVIDUAL,
                repository.current().resolveStage(new BigDecimal("13.20"), MapClusteringStage.BASIC_REGION)
        );
    }

    @Test
    void 배포용_정책_CSV를_읽는다() {
        CsvMapClusteringZoomPolicyRepository repository = new CsvMapClusteringZoomPolicyRepository(
                new ClassPathResource("map-clustering/stage-transitions.csv")
        );

        assertEquals("2026-09-02-v1", repository.current().policyVersion());
        assertEquals("2026-07-01", repository.current().regionDatasetVersion());
    }

    @Test
    void 잘못된_header를_거부한다() {
        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> repository("wrong,header\n")
        );

        assertTrue(exception.getMessage().contains("header"));
    }

    @Test
    void 행마다_policy_version이_다르면_거부한다() {
        String inconsistent = validCsv().replaceFirst("2026-09-02-v1,2026-07-01,2,3", "other,2026-07-01,2,3");

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> repository(inconsistent)
        );

        assertTrue(exception.getMessage().contains("line 3"));
        assertTrue(exception.getMessage().contains("policyVersion"));
    }

    @Test
    void 숫자로_해석할_수_없는_zoom과_행_번호를_알린다() {
        String invalidZoom = validCsv().replaceFirst(",10.00,0.20,11.00", ",zoom,0.20,11.00");

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> repository(invalidZoom)
        );

        assertTrue(exception.getMessage().contains("line 3"));
        assertTrue(exception.getMessage().contains("boundaryZoom"));
    }

    @Test
    void hysteresis가_경계보다_작지_않은_CSV_행을_거부한다() {
        String invalidRange = validCsv().replaceFirst(",7.50,0.20,8.50", ",0.20,0.20,8.50");

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> repository(invalidRange)
        );

        assertTrue(exception.getMessage().contains("line 2"));
        assertTrue(exception.getMessage().contains("hysteresis"));
    }

    private static CsvMapClusteringZoomPolicyRepository repository(String csv) {
        return new CsvMapClusteringZoomPolicyRepository(new ByteArrayResource(csv.getBytes()));
    }

    private static String validCsv() {
        return HEADER + "\n"
                + "2026-09-02-v1,2026-07-01,1,2,7.50,0.20,8.50\n"
                + "2026-09-02-v1,2026-07-01,2,3,10.00,0.20,11.00\n"
                + "2026-09-02-v1,2026-07-01,3,4,13.00,0.20,14.00\n";
    }
}
