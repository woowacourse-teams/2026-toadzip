package com.toadzip.backend.ingest.lh.collection.announcementcatalog.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@ActiveProfiles("test")
class LhCatalogConstructionRentalMigrationTest {

    @Autowired private DataSource dataSource;

    @Test
    void 건설임대가_아닌_목록_행만_지운다() throws Exception {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA lh_catalog_rental_migration_test");
            statement.execute("SET search_path TO lh_catalog_rental_migration_test");
            try {
                statement.execute("""
                        CREATE TABLE lh_announcement_catalog_entries (source_key varchar(200) PRIMARY KEY,
                            upper_announcement_type_code text, announcement_type_code text, supply_info_type_code text);
                        INSERT INTO lh_announcement_catalog_entries VALUES
                            ('rental', '06', '07', '062'), ('rental-padded', ' 06 ', '10', '063 '),
                            ('rental-tab', E'06\\t', '07', E'\\n061'),
                            ('newlywed-rental', '39', '42', '390'), ('newlywed-integrated', '39', '51', '390'),
                            ('land', '01', '01', '010'), ('commercial', '22', '22', '220'),
                            ('sale', '05', '05', '050'), ('purchase', '13', '26', '130'),
                            ('daycare', '06', '40', '064'), ('newlywed-sale', '39', '39', '390'),
                            ('missing-codes', NULL, NULL, NULL);
                        """);
                ScriptUtils.executeSqlScript(connection, new ClassPathResource(
                        "db/migration/V20261104_03__lh_catalog_construction_rental_only.sql"));

                List<String> kept = new ArrayList<>();
                try (var result = statement.executeQuery(
                        "SELECT source_key FROM lh_announcement_catalog_entries ORDER BY source_key")) {
                    while (result.next()) {
                        kept.add(result.getString(1));
                    }
                }
                assertThat(kept).containsExactly("newlywed-integrated", "newlywed-rental", "rental", "rental-padded",
                        "rental-tab");
            }
            finally {
                statement.execute("RESET search_path");
                statement.execute("DROP SCHEMA lh_catalog_rental_migration_test CASCADE");
            }
        }
    }
}
