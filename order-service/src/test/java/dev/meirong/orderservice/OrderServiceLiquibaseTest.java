package dev.meirong.orderservice;

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
class OrderServiceLiquibaseTest {

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
    void orderTablesExistInPublicSchemaAndDatabaseChangeLogIsIsolated() throws Exception {
        Set<String> publicTables = new HashSet<>();
        Set<String> liquibaseTables = new HashSet<>();

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            try (ResultSet rs = metaData.getTables(null, "public", null, new String[]{"TABLE"})) {
                while (rs.next()) {
                    publicTables.add(rs.getString("TABLE_NAME"));
                }
            }

            try (ResultSet rs = metaData.getTables(null, "order_schema", null, new String[]{"TABLE"})) {
                while (rs.next()) {
                    liquibaseTables.add(rs.getString("TABLE_NAME"));
                }
            }
        }

        assertTrue(publicTables.contains("orders"));
        assertTrue(publicTables.contains("order_items"));
        assertTrue(publicTables.contains("payments"));
        assertTrue(liquibaseTables.contains("databasechangelog"));
    }

    @Test
    void crossServiceForeignKeysAreAbsentWhileInternalForeignKeysRemain() throws Exception {
        Set<String> orderForeignKeys = foreignKeysFor("orders");
        Set<String> orderItemForeignKeys = foreignKeysFor("order_items");
        Set<String> paymentForeignKeys = foreignKeysFor("payments");

        assertEquals(Set.of(), orderForeignKeys);
        assertEquals(Set.of("fk_order_items_order_id"), orderItemForeignKeys);
        assertEquals(Set.of("fk_payments_order_id"), paymentForeignKeys);
    }

    private Set<String> foreignKeysFor(String tableName) throws Exception {
        Set<String> foreignKeys = new HashSet<>();

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            try (ResultSet rs = metaData.getImportedKeys(null, "public", tableName)) {
                while (rs.next()) {
                    foreignKeys.add(rs.getString("FK_NAME"));
                }
            }
        }

        return foreignKeys;
    }
}
