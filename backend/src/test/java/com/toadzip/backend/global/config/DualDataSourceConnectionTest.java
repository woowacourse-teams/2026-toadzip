package com.toadzip.backend.global.config;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class DualDataSourceConnectionTest {

    @Autowired
    private DataSource primaryDataSource;

    @Autowired
    @Qualifier("shared")
    private DataSource sharedDataSource;

    @Test
    void 기본_DB와_공유_DB에_각각_연결한다() throws SQLException {
        try (Connection primaryConnection = primaryDataSource.getConnection();
                Connection sharedConnection = sharedDataSource.getConnection()) {
            assertAll(
                    () -> assertNotSame(primaryDataSource, sharedDataSource),
                    () -> assertEquals("PostgreSQL", primaryConnection.getMetaData().getDatabaseProductName()),
                    () -> assertEquals("toadzip_test", primaryConnection.getCatalog()),
                    () -> assertEquals("PostgreSQL", sharedConnection.getMetaData().getDatabaseProductName()),
                    () -> assertEquals("toadzip_shared_test", sharedConnection.getCatalog())
            );
        }
    }
}
