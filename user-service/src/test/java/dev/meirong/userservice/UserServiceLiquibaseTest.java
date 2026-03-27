package dev.meirong.userservice;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("phase2")
@Testcontainers
class UserServiceLiquibaseTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("shopdb")
            .withUsername("postgres")
            .withPassword("postgres")
            .withInitScript("sql/create-phase2-schemas.sql");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private DataSource dataSource;

    @Test
    void usersTableExistsInPublicSchemaAndDatabaseChangeLogIsIsolated() throws Exception {
        Set<String> publicTables = new HashSet<>();
        Set<String> liquibaseTables = new HashSet<>();

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            try (ResultSet rs = metaData.getTables(null, "public", null, new String[]{"TABLE"})) {
                while (rs.next()) {
                    publicTables.add(rs.getString("TABLE_NAME"));
                }
            }

            try (ResultSet rs = metaData.getTables(null, "user_schema", null, new String[]{"TABLE"})) {
                while (rs.next()) {
                    liquibaseTables.add(rs.getString("TABLE_NAME"));
                }
            }
        }

        assertTrue(publicTables.contains("users"));
        assertTrue(liquibaseTables.contains("databasechangelog"));
    }

    @Test
    void usersTableHasNoCrossServiceForeignKeys() throws Exception {
        Set<String> importedKeys = new HashSet<>();

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            try (ResultSet rs = metaData.getImportedKeys(null, "public", "users")) {
                while (rs.next()) {
                    importedKeys.add(rs.getString("FK_NAME"));
                }
            }
        }

        assertEquals(Set.of(), importedKeys);
    }
}
