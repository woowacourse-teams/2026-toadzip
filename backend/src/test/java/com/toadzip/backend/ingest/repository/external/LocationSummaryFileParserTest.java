package com.toadzip.backend.ingest.repository.external;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.toadzip.backend.ingest.domain.LocationSummaryRecord;
import com.toadzip.backend.ingest.exception.exception.InvalidIngestRequestException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

class LocationSummaryFileParserTest {

    private static final Charset MS949 = Charset.forName("MS949");

    private final LocationSummaryFileParser parser = new LocationSummaryFileParser();

    @Test
    void 월전체_ZIP의_모든_지역_TXT를_CP949로_스트리밍_파싱한다() throws IOException {
        byte[] zip = zip(List.of(
                new Entry("entrc_seoul.txt", row(
                        "11140", "1", "1114010300", "서울특별시", "중구", "태평로1가",
                        "111402005001", "세종대로", "0", "110", "0", "953875.044172", "1951999.498732"
                ), MS949),
                new Entry("folder/entrc_jeju.txt", row(
                        "50130", "2", "5013010100", "제주특별자치도", "서귀포시", "성산읍",
                        "501302000001", "일출로", "0", "42", "3", "", ""
                ), MS949),
                new Entry("위치정보요약DB_레이아웃.pdf", "not data", StandardCharsets.UTF_8)
        ));
        List<LocationSummaryRecord> records = new ArrayList<>();

        LocationSummaryFileParseResult result = parser.parse(new ByteArrayInputStream(zip), records::add);

        assertThat(result.entryCount()).isEqualTo(2);
        assertThat(result.rowCount()).isEqualTo(2);
        assertThat(result.coordinateRowCount()).isOne();
        assertThat(result.missingCoordinateRowCount()).isOne();
        assertThat(result.entryNames()).containsExactlyInAnyOrder("entrc_seoul.txt", "entrc_jeju.txt");
        assertThat(result.provinceCodes()).containsExactlyInAnyOrder("11", "50");
        assertThat(records).extracting(LocationSummaryRecord::roadAddress)
                .containsExactly(
                        "서울특별시 중구 세종대로 110",
                        "제주특별자치도 서귀포시 성산읍 일출로 42-3"
                );
    }

    @Test
    void 컬럼_수가_다르면_파일명과_행번호를_포함해_거절한다() throws IOException {
        byte[] zip = zip(List.of(new Entry("entrc_seoul.txt", "one|two", StandardCharsets.UTF_8)));

        assertThatThrownBy(() -> parser.parse(new ByteArrayInputStream(zip), ignored -> { }))
                .isInstanceOf(InvalidIngestRequestException.class)
                .hasMessageContaining("entrc_seoul.txt")
                .hasMessageContaining("1번째 행")
                .hasMessageContaining("18개");
    }

    @Test
    void 위치정보요약_TXT가_없는_ZIP은_거절한다() throws IOException {
        byte[] zip = zip(List.of(new Entry("guide.pdf", "not data", StandardCharsets.UTF_8)));

        assertThatThrownBy(() -> parser.parse(new ByteArrayInputStream(zip), ignored -> { }))
                .isInstanceOf(InvalidIngestRequestException.class)
                .hasMessageContaining("TXT가 없습니다");
    }

    private String row(
            String districtCode,
            String entranceSerial,
            String legalDongCode,
            String provinceName,
            String districtName,
            String townName,
            String roadNameCode,
            String roadName,
            String underground,
            String mainNumber,
            String subNumber,
            String x,
            String y
    ) {
        return String.join("|",
                districtCode, entranceSerial, legalDongCode, provinceName, districtName, townName,
                roadNameCode, roadName, underground, mainNumber, subNumber,
                "building", "12345", "facility", "0", "administrativeDong", x, y
        );
    }

    private byte[] zip(List<Entry> entries) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            for (Entry entry : entries) {
                zip.putNextEntry(new ZipEntry(entry.name()));
                zip.write(entry.content().getBytes(entry.charset()));
                zip.closeEntry();
            }
        }
        return output.toByteArray();
    }

    private record Entry(String name, String content, Charset charset) {
    }
}
