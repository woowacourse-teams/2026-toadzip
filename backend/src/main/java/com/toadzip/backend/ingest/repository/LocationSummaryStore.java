package com.toadzip.backend.ingest.repository;

import com.toadzip.backend.ingest.domain.LocationSummaryRecord;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.postgresql.PGConnection;
import org.postgresql.copy.CopyIn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Repository;

@Repository
public class LocationSummaryStore {

    private static final String COPY_SQL = """
            COPY road_address_locations (
                road_name_code, underground, building_main_number, building_sub_number,
                entrance_serial, province_code, road_address, normalized_road_address, x, y
            ) FROM STDIN WITH (FORMAT text, DELIMITER E'\\t', NULL '\\N')
            """;

    private final DataSource dataSource;

    private final JdbcTemplate jdbcTemplate;

    public LocationSummaryStore(DataSource dataSource, JdbcTemplate jdbcTemplate) {
        this.dataSource = dataSource;
        this.jdbcTemplate = jdbcTemplate;
    }

    public long count() {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM road_address_locations", Long.class);
        return count == null ? 0 : count;
    }

    public void truncate() {
        jdbcTemplate.execute("TRUNCATE TABLE road_address_locations");
    }

    public CopyWriter openWriter() {
        try {
            Connection connection = DataSourceUtils.getConnection(dataSource);
            CopyIn copy = connection.unwrap(PGConnection.class).getCopyAPI().copyIn(COPY_SQL);
            return new CopyWriter(copy);
        }
        catch (SQLException exception) {
            throw writeFailure(exception);
        }
    }

    public static final class CopyWriter implements AutoCloseable {

        private final CopyIn copy;

        private boolean completed;

        private CopyWriter(CopyIn copy) {
            this.copy = copy;
        }

        public void write(LocationSummaryRecord record) {
            String line = String.join("\t",
                    escape(record.roadNameCode()),
                    escape(record.underground()),
                    Integer.toString(record.buildingMainNumber()),
                    Integer.toString(record.buildingSubNumber()),
                    escape(record.entranceSerial()),
                    escape(record.provinceCode()),
                    escape(record.roadAddress()),
                    escape(record.normalizedRoadAddress()),
                    nullable(record.x()),
                    nullable(record.y())
            ) + '\n';
            byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
            try {
                copy.writeToCopy(bytes, 0, bytes.length);
            }
            catch (SQLException exception) {
                throw writeFailure(exception);
            }
        }

        public long complete() {
            try {
                long insertedRowCount = copy.endCopy();
                completed = true;
                return insertedRowCount;
            }
            catch (SQLException exception) {
                throw writeFailure(exception);
            }
        }

        @Override
        public void close() {
            if (completed || !copy.isActive()) {
                return;
            }
            try {
                copy.cancelCopy();
            }
            catch (SQLException exception) {
                throw writeFailure(exception);
            }
        }

        private static String nullable(Object value) {
            return value == null ? "\\N" : value.toString();
        }

        private static String escape(String value) {
            return value
                    .replace("\\", "\\\\")
                    .replace("\t", "\\t")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r");
        }
    }

    private static IllegalStateException writeFailure(SQLException exception) {
        return new IllegalStateException("위치정보요약DB를 DB에 적재하지 못했습니다.", exception);
    }
}
