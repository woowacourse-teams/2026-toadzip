package com.toadzip.backend.ingest.location.repository.external;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.toadzip.backend.ingest.exception.exception.InvalidIngestRequestException;
import com.toadzip.backend.ingest.location.domain.LocationSummaryRecord;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
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
        assertThat(result.provinceCodesByEntry())
                .containsEntry("entrc_seoul.txt", Set.of("11"))
                .containsEntry("entrc_jeju.txt", Set.of("50"));
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

    @Test
    void 비어_있는_지역_TXT는_빈_시도코드_집합으로_기록한다() throws IOException {
        byte[] zip = zip(List.of(
                new Entry("entrc_seoul.txt", "", MS949),
                new Entry("entrc_jeju.txt", row(
                        "50130", "2", "5013010100", "제주특별자치도", "서귀포시", "성산읍",
                        "501302000001", "일출로", "0", "42", "3", "", ""
                ), MS949)
        ));

        LocationSummaryFileParseResult result = parser.parse(new ByteArrayInputStream(zip), ignored -> { });

        assertThat(result.provinceCodesByEntry())
                .containsEntry("entrc_seoul.txt", Set.of())
                .containsEntry("entrc_jeju.txt", Set.of("50"));
    }

    @Test
    void UTF8_한글이_8192바이트_표본_경계에_걸려도_UTF8로_파싱한다() throws IOException {
        String prefix = String.join(
                "|", "11140", "1", "1114010300", "서울특별시", "중구", ""
        );
        int fillerLength = 8_191 - prefix.getBytes(StandardCharsets.UTF_8).length;
        String townName = "a".repeat(fillerLength) + "가";
        String content = row(
                "11140", "1", "1114010300", "서울특별시", "중구", townName,
                "111402005001", "세종대로", "0", "110", "0", "953875.044172", "1951999.498732"
        );
        List<LocationSummaryRecord> records = new ArrayList<>();

        parser.parse(
                new ByteArrayInputStream(zip(List.of(new Entry(
                        "entrc_seoul.txt", content, StandardCharsets.UTF_8
                )))),
                records::add
        );

        assertThat(records).singleElement()
                .extracting(LocationSummaryRecord::townName)
                .asString()
                .contains("가");
    }

    @Test
    void UTF8_4바이트_문자가_8192바이트_표본_경계에_걸려도_UTF8로_파싱한다() throws IOException {
        String fourByteCharacter = "😀";
        String prefix = String.join(
                "|", "11140", "1", "1114010300", "province", "district", ""
        );
        int fillerLength = 8_191 - prefix.getBytes(StandardCharsets.UTF_8).length;
        String townName = "a".repeat(fillerLength) + fourByteCharacter;
        String content = row(
                "11140", "1", "1114010300", "province", "district", townName,
                "111402005001", "road", "0", "110", "0", "953875.044172", "1951999.498732"
        );
        List<LocationSummaryRecord> records = new ArrayList<>();

        parser.parse(
                new ByteArrayInputStream(zip(List.of(new Entry(
                        "entrc_seoul.txt", content, StandardCharsets.UTF_8
                )))),
                records::add
        );

        assertThat(records).singleElement()
                .extracting(LocationSummaryRecord::townName)
                .asString()
                .endsWith(fourByteCharacter);
    }

    @Test
    void CP949_한글이_8192바이트_표본_경계에_걸려도_CP949로_파싱한다() throws IOException {
        String cp949Character = new String(new byte[] {(byte) 0xE0, (byte) 0xA1}, MS949);
        String prefix = String.join(
                "|", "11140", "1", "1114010300", "province", "district", ""
        );
        int fillerLength = 8_191 - prefix.getBytes(MS949).length;
        String townName = "a".repeat(fillerLength) + cp949Character;
        String content = row(
                "11140", "1", "1114010300", "province", "district", townName,
                "111402005001", "road", "0", "110", "0", "953875.044172", "1951999.498732"
        );
        List<LocationSummaryRecord> records = new ArrayList<>();

        parser.parse(
                new ByteArrayInputStream(zip(List.of(new Entry(
                        "entrc_seoul.txt", content, MS949
                )))),
                records::add
        );

        assertThat(records).singleElement()
                .extracting(LocationSummaryRecord::townName)
                .asString()
                .endsWith(cp949Character);
    }

    @Test
    void UTF8과_CP949_표본이_모두_유효하지_않으면_거절한다() throws IOException {
        byte[] zip = zip(List.of(new Entry("entrc_seoul.txt", new byte[] {(byte) 0xFF, (byte) 0xFF})));

        assertThatThrownBy(() -> parser.parse(new ByteArrayInputStream(zip), ignored -> { }))
                .isInstanceOf(InvalidIngestRequestException.class)
                .hasMessageContaining("인코딩");
    }

    @Test
    void 표본_뒤의_본문에_잘못된_바이트가_있으면_거절한다() throws IOException {
        String firstRow = row(
                "11140", "1", "1114010300", "서울특별시", "중구", "a".repeat(8_192),
                "111402005001", "세종대로", "0", "110", "0", "953875.044172", "1951999.498732"
        ) + "\n";
        byte[] validPrefix = firstRow.getBytes(StandardCharsets.UTF_8);
        byte[] content = Arrays.copyOf(validPrefix, validPrefix.length + 1);
        content[content.length - 1] = (byte) 0xFF;
        byte[] zip = zip(List.of(new Entry("entrc_seoul.txt", content)));

        assertThatThrownBy(() -> parser.parse(new ByteArrayInputStream(zip), ignored -> { }))
                .isInstanceOf(InvalidIngestRequestException.class)
                .hasMessageContaining("인코딩");
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
                zip.write(entry.content());
                zip.closeEntry();
            }
        }
        return output.toByteArray();
    }

    private record Entry(String name, byte[] content) {

        private Entry(String name, String content, Charset charset) {
            this(name, content.getBytes(charset));
        }
    }
}
