package com.toadzip.backend.ingest.location.repository;

import com.toadzip.backend.ingest.location.domain.LocationSummaryRecord;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import javax.sql.DataSource;
import org.postgresql.PGConnection;
import org.postgresql.copy.CopyIn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class LocationSummaryStore {

    private static final String COPY_SQL = """
            COPY location_summary_upload (
                road_name_code, underground, building_main_number, building_sub_number,
                entrance_serial, province_code, road_address, normalized_road_address, x, y, coordinate_valid
            ) FROM STDIN WITH (FORMAT text, DELIMITER E'\\t', NULL '\\N')
            """;

    private final DataSource dataSource;

    private final JdbcTemplate jdbcTemplate;

    public LocationSummaryStore(DataSource dataSource, JdbcTemplate jdbcTemplate) {
        this.dataSource = dataSource;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public CopyWriter openWriter() {
        jdbcTemplate.execute("""
                CREATE TEMPORARY TABLE location_summary_upload
                (LIKE road_address_locations INCLUDING DEFAULTS INCLUDING INDEXES, coordinate_valid boolean NOT NULL)
                ON COMMIT DROP
                """);
        try {
            Connection connection = DataSourceUtils.getConnection(dataSource);
            CopyIn copy = connection.unwrap(PGConnection.class).getCopyAPI().copyIn(COPY_SQL);
            return new CopyWriter(copy);
        }
        catch (SQLException exception) {
            throw writeFailure(exception);
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public long mergeUploadedLocations() {
        Long replaced = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM road_address_locations existing
                JOIN location_summary_upload uploaded USING
                    (road_name_code, underground, building_main_number, building_sub_number, entrance_serial)
                WHERE uploaded.coordinate_valid
                """, Long.class);
        jdbcTemplate.execute("""
                INSERT INTO road_address_locations AS existing
                    (road_name_code, underground, building_main_number, building_sub_number, entrance_serial,
                     province_code, road_address, normalized_road_address, x, y)
                SELECT road_name_code, underground, building_main_number, building_sub_number, entrance_serial,
                       province_code, road_address, normalized_road_address, x, y
                FROM location_summary_upload
                ON CONFLICT (road_name_code, underground, building_main_number, building_sub_number, entrance_serial)
                DO NOTHING
                """);
        jdbcTemplate.execute("""
                UPDATE road_address_locations existing
                SET province_code = uploaded.province_code, road_address = uploaded.road_address,
                    normalized_road_address = uploaded.normalized_road_address, x = uploaded.x, y = uploaded.y
                FROM location_summary_upload uploaded
                WHERE uploaded.coordinate_valid AND existing.road_name_code = uploaded.road_name_code
                    AND existing.underground = uploaded.underground
                    AND existing.building_main_number = uploaded.building_main_number
                    AND existing.building_sub_number = uploaded.building_sub_number
                    AND existing.entrance_serial = uploaded.entrance_serial
                """);
        return Objects.requireNonNullElse(replaced, 0L);
    }

    public static final class CopyWriter implements AutoCloseable {

        private final CopyIn copy;

        private boolean completed;

        private CopyWriter(CopyIn copy) {
            this.copy = copy;
        }

        public void write(LocationSummaryRecord record, boolean coordinateValid) {
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
                    nullable(record.y()),
                    Boolean.toString(coordinateValid)
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
            return Objects.toString(value, "\\N");
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
